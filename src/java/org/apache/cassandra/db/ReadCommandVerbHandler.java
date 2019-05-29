/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.security.Key;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.Treas.DoubleTreasTag;
import org.apache.cassandra.Treas.TreasMap;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.service.reads.AbstractReadExecutor;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.ByteBufferUtil;

public class ReadCommandVerbHandler implements IVerbHandler<ReadCommand>
{
    protected IVersionedSerializer<ReadResponse> serializer()
    {
        return ReadResponse.serializer;
    }

    private static final Logger logger = LoggerFactory.getLogger(ReadCommandVerbHandler.class);

    public void doVerb(MessageIn<ReadCommand> message, int id)
    {
        logger.debug("I am here in ReadCommandDoverb");
        if (StorageService.instance.isBootstrapMode())
        {
            throw new RuntimeException("Cannot service reads while bootstrapping!");
        }

        ReadCommand command = message.payload;

        command.setMonitoringTime(message.constructionTime, message.isCrossNode(), message.getTimeout(), message.getSlowQueryTimeout());

        // TODO: In Memory:
        // UnfilteredPartitionIterators.filter(rr.makeIterator(command, doubleTreasTag), command.nowInSec())
        // doubleTreasTag will be put into the Iterator

        // Seems like doing Twice Read here.
        ReadResponse dummyResponse;
        ReadResponse response;

        SinglePartitionReadCommand singlePartitionReadCommand = (SinglePartitionReadCommand) command;
        DecoratedKey decoratedKeyKey = singlePartitionReadCommand.partitionKey();

        try (ReadExecutionController executionController = command.executionController();
             UnfilteredPartitionIterator iterator = command.executeLocally(executionController))
        {
            dummyResponse = command.createResponse(iterator);

            DoubleTreasTag doubleTreasTag = new DoubleTreasTag();

            // Indicate that this Iterator is going to sent to the Coordinator
            doubleTreasTag.setTagIndicator();
            doubleTreasTag.setKey(decoratedKeyKey);

            UnfilteredPartitionIterator sendIterator = dummyResponse.makeIterator(command, doubleTreasTag);
            logger.debug("Finish Create our Iterator, and now make a new response");
            response = command.createResponse(sendIterator);
        }

        if (!command.complete())
        {
            Tracing.trace("Discarding partial response to {} (timed out)", message.from);
            MessagingService.instance().incrementDroppedMessages(message, message.getLifetimeInMS());
            return;
        }

        logger.debug("Send Response");
        Tracing.trace("Enqueuing response to {}", message.from);
        MessageOut<ReadResponse> reply = new MessageOut<>(MessagingService.Verb.REQUEST_RESPONSE, response, serializer());
        MessagingService.instance().sendReply(reply, id, message.from);
    }
}
