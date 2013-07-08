/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.server.distributed.task;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import com.orientechnologies.orient.core.db.record.ORecordOperation;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.distributed.ODistributedException;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager.EXECUTION_MODE;
import com.orientechnologies.orient.server.distributed.ODistributedThreadLocal;
import com.orientechnologies.orient.server.distributed.OServerOfflineException;
import com.orientechnologies.orient.server.distributed.OStorageSynchronizer;
import com.orientechnologies.orient.server.journal.ODatabaseJournal.OPERATION_TYPES;

/**
 * Distributed create record task used for synchronization.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public abstract class OAbstractRecordDistributedTask<T> extends OAbstractDistributedTask<T> {
  protected ORecordId      rid;
  protected ORecordVersion version;

  public OAbstractRecordDistributedTask() {
  }

  public OAbstractRecordDistributedTask(final OServer iServer, final ODistributedServerManager iDistributedSrvMgr,
      final String iDbName, final EXECUTION_MODE iMode, final ORecordId iRid, final ORecordVersion iVersion) {
    super(iServer, iDistributedSrvMgr, iDbName, iMode);
    this.rid = iRid;
    this.version = iVersion;
  }

  public OAbstractRecordDistributedTask(final long iRunId, final long iOperationId, final ORecordId iRid,
      final ORecordVersion iVersion) {
    super(iRunId, iOperationId);
    this.rid = iRid;
    this.version = iVersion;
  }

  protected abstract OPERATION_TYPES getOperationType();

  protected abstract T executeOnLocalNode(final OStorageSynchronizer dbSynchronizer);

  public T call() {
    // if (rid != null && version != null)
    // ODistributedServerLog.info(this, getDistributedServerManager().getLocalNodeId(), getNodeSource(), DIRECTION.IN,
    // "operation %s against record %s/%s v.%s", getName(), databaseName, rid.toString(), version.toString());

    final ODistributedServerManager dManager = getDistributedServerManager();
    if (status != STATUS.ALIGN && !dManager.checkStatus("online") && !getNodeSource().equals(dManager.getLocalNodeId()))
      // NODE NOT ONLINE, REFUSE THE OPEPRATION
      throw new OServerOfflineException(dManager.getLocalNodeId(), dManager.getStatus(),
          "Cannot execute the operation because the server is offline: current status: " + dManager.getStatus());

    final OStorageSynchronizer dbSynchronizer = getDatabaseSynchronizer();

    final OPERATION_TYPES opType = getOperationType();

    // LOG THE OPERATION BEFORE TO SEND TO OTHER NODES
    final long operationLogOffset;
    if (opType != null)
      try {
        operationLogOffset = dbSynchronizer.getLog().journalOperation(runId, operationSerial, opType, this);
      } catch (IOException e) {
        ODistributedServerLog.error(this, getDistributedServerManager().getLocalNodeId(), getNodeSource(), DIRECTION.IN,
            "error on logging operation %s %s/%s v.%s", e, getName(), databaseName, rid.toString(), version.toString());
        throw new ODistributedException("Error on logging operation", e);
      }
    else
      operationLogOffset = -1;

    ODistributedThreadLocal.INSTANCE.set(getNodeSource());
    try {
      // EXECUTE IT LOCALLY
      final T localResult = executeOnLocalNode(dbSynchronizer);

      if (opType != null)
        try {
          setAsCompleted(dbSynchronizer, operationLogOffset);
        } catch (IOException e) {
          ODistributedServerLog.error(this, getDistributedServerManager().getLocalNodeId(), getNodeSource(), DIRECTION.IN,
              "error on changing the log status for operation %s %s/%s v.%s", e, getName(), databaseName, rid.toString(),
              version.toString());
          throw new ODistributedException("Error on changing the log status", e);
        }

      if (status == STATUS.DISTRIBUTE && getDistributedServerManager().getLocalNodeId().equals(getNodeSource())) {
        // SEND OPERATION ACROSS THE CLUSTER TO THE TARGET NODES
        final Map<String, Object> distributedResult = dbSynchronizer.propagateOperation(ORecordOperation.CREATED, rid, this);

        if (distributedResult != null)
          for (Entry<String, Object> entry : distributedResult.entrySet()) {
            final String remoteNode = entry.getKey();
            final Object remoteResult = entry.getValue();

            if ((localResult == null && remoteResult != null) || (localResult != null && remoteResult == null)
                || !localResult.equals(remoteResult)) {
              // CONFLICT
              handleConflict(remoteNode, localResult, remoteResult);
            }
          }
      }

      if (mode != EXECUTION_MODE.FIRE_AND_FORGET)
        return localResult;

      // FIRE AND FORGET MODE: AVOID THE PAYLOAD AS RESULT
      return null;

    } finally {
      ODistributedThreadLocal.INSTANCE.set(null);
    }
  }

  @Override
  public String toString() {
    return getName() + "(" + rid + " v." + version + ")";
  }

  public ORecordId getRid() {
    return rid;
  }

  public void setRid(ORecordId rid) {
    this.rid = rid;
  }

  public ORecordVersion getVersion() {
    return version;
  }

  public void setVersion(ORecordVersion version) {
    this.version = version;
  }
}
