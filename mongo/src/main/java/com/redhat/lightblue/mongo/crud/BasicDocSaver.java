/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.mongo.crud;

import java.io.IOException;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.DBCursor;
import com.mongodb.WriteResult;
import com.mongodb.BulkWriteError;
import com.mongodb.BulkWriteException;
import com.mongodb.BulkWriteOperation;
import com.redhat.lightblue.crud.CRUDOperation;
import com.redhat.lightblue.crud.CRUDOperationContext;
import com.redhat.lightblue.crud.CrudConstants;
import com.redhat.lightblue.crud.DocCtx;
import com.redhat.lightblue.eval.FieldAccessRoleEvaluator;
import com.redhat.lightblue.interceptor.InterceptPoint;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.Field;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonDoc;
import com.redhat.lightblue.util.Path;

/**
 * Basic doc saver with no transaction support
 */
public class BasicDocSaver implements DocSaver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BasicDocSaver.class);

    private int batchSize=64;

    private final FieldAccessRoleEvaluator roleEval;
    private final Translator translator;
    private final EntityMetadata md;

    private final Field[] idFields;
    private final Path[] idPaths;
    private final String[] mongoIdFields;


    /**
     * Creates a doc saver with the given translator and role evaluator
     */
    public BasicDocSaver(Translator translator,
                         FieldAccessRoleEvaluator roleEval,
                         EntityMetadata md) {
        this.translator = translator;
        this.roleEval = roleEval;
        this.md=md;
        
        Field[] idf=md.getEntitySchema().getIdentityFields();
        if(idf==null||idf.length==0) {
            // Assume _id is the id
            idFields=new Field[] {(Field)md.resolve(Translator.ID_PATH)};
        } else {
            idFields=idf;
        }
        idPaths=new Path[idFields.length];
        mongoIdFields=new String[idFields.length];
        for(int i=0;i<mongoIdFields.length;i++) {
            idPaths[i]=md.getEntitySchema().getEntityRelativeFieldName(idFields[i]);
            mongoIdFields[i]=Translator.translatePath(idPaths[i]);
        }
    }

    private final class DocInfo {
        final DBObject newDoc; // translated input doc to be written
        final DocCtx inputDoc; // The doc coming from client
        DBObject oldDoc; // The doc in the db
        Object[] id;

        public DocInfo(DBObject newDoc,DocCtx inputDoc) {
            this.newDoc=newDoc;
            this.inputDoc=inputDoc;
        }

        public BasicDBObject getIdQuery() {
            BasicDBObject q=new BasicDBObject();
            for(int i=0;i<id.length;i++) {
                q.append(mongoIdFields[i],id[i]);
            }
            return q;
        }
    }
    
    @Override
    public void saveDocs(CRUDOperationContext ctx,
                         Op op,
                         boolean upsert,
                         DBCollection collection,
                         DBObject[] dbObjects,
                         DocCtx[] inputDocs) {
        // Operate in batches
        List<DocInfo> batch=new ArrayList<>(batchSize);
        for(int i=0;i<dbObjects.length;i++) {
            DocInfo item=new DocInfo(dbObjects[i],inputDocs[i]);
            batch.add(item);
            if(batch.size()>=batchSize) {
                saveDocs(ctx,op,upsert,collection,batch);
                batch.clear();
            }
        }
        if(!batch.isEmpty()) {
            saveDocs(ctx,op,upsert,collection,batch);
        }
    }

    private void saveDocs(CRUDOperationContext ctx,
                          Op op,
                          boolean upsert,
                          DBCollection collection,
                          List<DocInfo> batch) {       
        // If this is a save operation, we have to load the existing DB objects
        if(op==DocSaver.Op.save) {
            LOGGER.debug("Retrieving existing {} documents for save operation",batch.size());
            List<BasicDBObject> idQueries=new ArrayList<>(batch.size());
            for(DocInfo doc:batch) {
                doc.id=getFieldValues(doc.newDoc,idPaths);
                if(!isNull(doc.id)) {
                    idQueries.add(doc.getIdQuery());
                }
            }
            if(!idQueries.isEmpty()) {
                BasicDBObject retrievalq=new BasicDBObject("$or",idQueries);
                LOGGER.debug("Existing document retrieval query={}",retrievalq);
                try (DBCursor cursor=collection.find(retrievalq,null)) {
                    List<DBObject> results=cursor.toArray();
                    LOGGER.debug("Retrieved {} docs",results.size());
                    
                    // Now associate the docs in the retrieved results with the docs in the batch
                    for(DBObject dbDoc:results) {
                        // Get the id from the doc
                        Object[] id=getFieldValues(dbDoc,idPaths);
                        // Find this doc in the batch
                        DocInfo doc=findDoc(batch,id);
                        if(doc!=null) {
                            doc.oldDoc=dbDoc;
                        } else {
                            LOGGER.warn("Cannot find doc with id={}",id);
                        }
                    }
                }
            }
        }
        // Some docs in the batch will be inserted, some saved, based on the operation. Lets decide that now
        List<DocInfo> saveList;
        List<DocInfo> insertList;
        if(op==DocSaver.Op.insert) {
            saveList=new ArrayList<>();
            insertList=batch;
        } else {
            // This is a save operation
            saveList=new ArrayList<>();
            insertList=new ArrayList<>();
            for(DocInfo doc:batch) {
                if(doc.oldDoc==null) {
                    // there is no doc in the db
                    if(upsert) {
                        // This is an insertion
                        insertList.add(doc);
                    } else {
                        // This is an invalid  request
                        LOGGER.warn("Invalid request, cannot update or insert");
                        doc.inputDoc.addError(Error.get(op.toString(), MongoCrudConstants.ERR_SAVE_ERROR, "Invalid request"));
                    }
                } else {
                    // There is a doc in the db
                    saveList.add(doc);
                }
            }
        }
        LOGGER.debug("Save docs={}, insert docs={}, error docs={}",saveList.size(),insertList.size(),batch.size()-saveList.size()-insertList.size());
        insertDocs(ctx,collection,insertList);
        updateDocs(ctx,collection,saveList);
    }

    private void insertDocs(CRUDOperationContext ctx,
                            DBCollection collection,
                            List<DocInfo> list) {
        if(!list.isEmpty()) {
            LOGGER.debug("Inserting {} docs",list.size());
            if (!md.getAccess().getInsert().hasAccess(ctx.getCallerRoles())) {
                for(DocInfo doc:list) {
                    doc.inputDoc.addError(Error.get("insert",
                                                    MongoCrudConstants.ERR_NO_ACCESS,
                                                    "insert:" + md.getName()));
                }
            } else {
                List<DocInfo> insertionAttemptList=new ArrayList<>(list.size());
                for(DocInfo doc:list) {
                    Set<Path> paths = roleEval.getInaccessibleFields_Insert(doc.inputDoc);
                    LOGGER.debug("Inaccessible fields:{}", paths);
                    if (paths == null || paths.isEmpty()) {
                        try {
                            Translator.populateDocHiddenFields(doc.newDoc,md);
                            insertionAttemptList.add(doc);                        
                        } catch(IOException e) {
                            doc.inputDoc.addError(Error.get("insert",MongoCrudConstants.ERR_TRANSLATION_ERROR));
                        }
                    } else {
                        for(Path path : paths){
                            doc.inputDoc.addError(Error.get("insert", CrudConstants.ERR_NO_FIELD_INSERT_ACCESS, path.toString()));
                        }
                    }
                }
                LOGGER.debug("After access checks, inserting {} docs",insertionAttemptList.size());
                if(!insertionAttemptList.isEmpty()) {
                    BulkWriteOperation bw=collection.initializeUnorderedBulkOperation();
                    for(DocInfo doc:insertionAttemptList) {
                        ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.PRE_CRUD_INSERT_DOC, ctx, doc.inputDoc);
                        bw.insert(doc.newDoc);
                        doc.inputDoc.setCRUDOperationPerformed(CRUDOperation.INSERT);
                   }
                    try {
                        LOGGER.debug("Bulk inserting docs");
                        bw.execute();
                    } catch (BulkWriteException bwe) {
                        LOGGER.error("Bulk write exception",bwe);
                        handleBulkWriteError(bwe.getWriteErrors(),"insert",insertionAttemptList);
                    } catch(RuntimeException e) {
                        LOGGER.error("Exception",e);
                        throw e;
                    } finally {
                        for(DocInfo doc:insertionAttemptList) {
                            if(!doc.inputDoc.hasErrors()) {
                                ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.POST_CRUD_INSERT_DOC, ctx, doc.inputDoc);
                            }
                        }
                    }
                }
            }
        }
    }

    private void updateDocs(CRUDOperationContext ctx,
                            DBCollection collection,
                            List<DocInfo> list) {
        if(!list.isEmpty()) {
            LOGGER.debug("Updating {} docs",list.size());            
            if (!md.getAccess().getUpdate().hasAccess(ctx.getCallerRoles())) {
                for(DocInfo doc:list) {
                    doc.inputDoc.addError(Error.get("update",
                                                    CrudConstants.ERR_NO_ACCESS, "update:" + md.getName()));
                }
            } else {
                List<DocInfo> updateAttemptList=new ArrayList<>(list.size());
                BsonMerge merge=new BsonMerge(md);
                for(DocInfo doc:list) {
                    JsonDoc oldDoc = translator.toJson(doc.oldDoc);
                    doc.inputDoc.setOriginalDocument(oldDoc);
                    Set<Path> paths = roleEval.getInaccessibleFields_Update(doc.inputDoc, oldDoc);
                    if (paths == null || paths.isEmpty()) {
                        try {
                            ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.PRE_CRUD_UPDATE_DOC, ctx, doc.inputDoc);
                            merge.merge(doc.oldDoc,doc.newDoc);
                            Translator.populateDocHiddenFields(doc.newDoc, md);
                            updateAttemptList.add(doc);
                        } catch (Exception e) {
                            doc.inputDoc.addError(Error.get("update",MongoCrudConstants.ERR_TRANSLATION_ERROR));
                        }
                    } else {
                        doc.inputDoc.addError(Error.get("update",
                                                        CrudConstants.ERR_NO_FIELD_UPDATE_ACCESS, paths.toString()));
                    }
                }
                LOGGER.debug("After checks and merge, updating {} docs",updateAttemptList.size());
                if(!updateAttemptList.isEmpty()) {
                     BulkWriteOperation bw=collection.initializeUnorderedBulkOperation();
                     for(DocInfo doc:updateAttemptList) {
                         bw.find(new BasicDBObject("_id",doc.oldDoc.get("_id"))).replaceOne(doc.newDoc);
                         doc.inputDoc.setCRUDOperationPerformed(CRUDOperation.UPDATE);
                     }
                     try {
                         LOGGER.debug("Bulk updating docs");
                         bw.execute();
                     } catch(BulkWriteException bwe) {
                        LOGGER.error("Bulk write exception",bwe);
                        handleBulkWriteError(bwe.getWriteErrors(),"update",updateAttemptList);
                    } catch(RuntimeException e) {
                     } finally {
                         for(DocInfo doc:updateAttemptList) {
                             if(!doc.inputDoc.hasErrors()) {
                                 ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.POST_CRUD_UPDATE_DOC, ctx, doc.inputDoc);
                             }
                         }
                     }
                }
            }
        }
    }

    private void handleBulkWriteError(List<BulkWriteError> errors,String operation,List<DocInfo> docs) {
        for(BulkWriteError e:errors) {
            DocInfo doc=docs.get(e.getIndex());
            if(e.getCode()==11000||e.getCode()==11001) {
                doc.inputDoc.addError(Error.get("update",MongoCrudConstants.ERR_DUPLICATE,e.getMessage()));
            } else {
                doc.inputDoc.addError(Error.get("update",MongoCrudConstants.ERR_SAVE_ERROR,e.getMessage()));
            }
        }
    }

    private DocInfo findDoc(List<DocInfo> list,Object[] id) {
        for(DocInfo doc:list) {
            if(idEquals(doc.id,id)) {
                return doc;
            }
        }
        return null;
    }

    private boolean idEquals(Object[] id1,Object[] id2) {
        if(id1!=null&&id2!=null) {
            for(int i=0;i<id1.length;i++) {
                if( (id1[i]==null&&id2[i]==null) ||
                    (id1[i]!=null&&id2[i]!=null&&id1[i].equals(id2[i]))) {
                    ;
                } else {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Return the values for the fields
     */
    private Object[] getFieldValues(DBObject object,Path[] fields) {
        Object[] ret=new Object[fields.length];
        for(int i=0;i<ret.length;i++)
            ret[i]=Translator.getDBObject(object,fields[i]);
        return ret;
    }
        
    /**
     * Return if all values are null
     */
    private boolean isNull(Object[] values) {
        if(values!=null) {
            for(int i=0;i<values.length;i++) {
                if(values[i]!=null) {
                    return false;
                }
            }
        }
        return true;
    }
}