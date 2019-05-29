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
package org.apache.cassandra.service.reads;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;

import org.apache.cassandra.Treas.DoubleTreasTag;
import org.apache.cassandra.Treas.TreasConfig;
import org.apache.cassandra.Treas.TreasMap;
import org.apache.cassandra.Treas.TreasTag;
import org.apache.cassandra.Treas.TreasTagMap;
import org.apache.cassandra.Treas.TreasValueID;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.RowIterator;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.ABDColomns;
import org.apache.cassandra.service.ABDTag;
import org.apache.cassandra.service.reads.repair.ReadRepair;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

public class DigestResolver extends ResponseResolver
{
    private volatile ReadResponse dataResponse;

    public DigestResolver(Keyspace keyspace, ReadCommand command, ConsistencyLevel consistency, ReadRepair readRepair, int maxResponseCount)
    {
        super(keyspace, command, consistency, readRepair, maxResponseCount);
        Preconditions.checkArgument(command instanceof SinglePartitionReadCommand,
                                    "DigestResolver can only be used with SinglePartitionReadCommand commands");
    }

    @Override
    public void preprocess(MessageIn<ReadResponse> message)
    {
        super.preprocess(message);
        if (dataResponse == null && !message.payload.isDigestResponse())
            dataResponse = message.payload;
    }

    // this is the original method, NoopReadRepair has a call to this method
    // simply change the method signature to ReadResponse getData() will raise an compiler error
    public PartitionIterator getData()
    {
        assert isDataPresent();
        return UnfilteredPartitionIterators.filter(dataResponse.makeIterator(command), command.nowInSec());
    }

    // this is a new method for AbstractReadExecutor, which may want to use ReadResponse more than once
    public ReadResponse getReadResponse()
    {
        assert isDataPresent();
        return dataResponse;
    }

    public boolean responsesMatch()
    {
        long start = System.nanoTime();

        // validate digests against each other; return false immediately on mismatch.
        ByteBuffer digest = null;
        for (MessageIn<ReadResponse> message : responses)
        {
            ReadResponse response = message.payload;

            ByteBuffer newDigest = response.digest(command);
            if (digest == null)
                digest = newDigest;
            else if (!digest.equals(newDigest))
                // rely on the fact that only single partition queries use digests
                return false;
        }

        if (logger.isTraceEnabled())
            logger.trace("responsesMatch: {} ms.", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        return true;
    }

    public ReadResponse extractMaxZResponse()
    {
        // check all data responses,
        // extract the one with max z value
        ABDTag maxTag = new ABDTag();
        ReadResponse maxZResponse = null;

        ColumnIdentifier zIdentifier = new ColumnIdentifier(ABDColomns.TAG, true);
        for (MessageIn<ReadResponse> message : responses)
        {
            ReadResponse response = message.payload;

            // check if the response is indeed a data response
            // we shouldn't get a digest response here
            assert response.isDigestResponse() == false;

            // get the partition iterator corresponding to the
            // current data response
            PartitionIterator pi = UnfilteredPartitionIterators.filter(response.makeIterator(command), command.nowInSec());

            // get the z value column
            while(pi.hasNext())
            {
                // zValueReadResult.next() returns a RowIterator
                RowIterator ri = pi.next();
                while(ri.hasNext())
                {
                    // todo: the entire row is read for the sake of development
                    // future improvement could be made

                    ABDTag curTag = new ABDTag();
                    for(Cell c : ri.next().cells())
                    {
                        if(c.column().name.equals(zIdentifier)) {
                            curTag = ABDTag.deserialize(c.value());
                        }
                    }

                    if(curTag.isLarger(maxTag))
                    {
                        maxTag = curTag;
                        maxZResponse = response;
                    }
                }
            }
        }
        return maxZResponse;
    }

    public void fetchTargetTagValue(DoubleTreasTag doubleTreasTag) {
        logger.debug("Inside awaitResponsesTreasTagValue");

        System.out.println("Inside awaitResponsesTreasTagValue");

        HashMap<TreasTag, Integer> quorumMap = new HashMap<>();
        HashMap<TreasTag, List<String>> decodeMap = new HashMap<>();
        TreasTag quorumTagMax = new TreasTag();
        TreasTag decodeTagMax = new TreasTag();
        List<String> decodeValMax = null;


        String keySpaceName = "";
        DecoratedKey key = null;
        TableMetadata tableMetadata = null;



        logger.debug("Before Digest Match");
        logger.debug("Message size is" + this.getMessages().size());
        System.out.println("Message size is" + this.getMessages().size());
        // Each readResponse represents a response from a Replica
        for (MessageIn<ReadResponse> message : this.getMessages())
        {
            ReadResponse response = message.payload;
            if (message.from.equals(FBUtilities.getLocalAddressAndPort()))
            {
                logger.debug("This message is from me");
            }

            assert response.isDigestResponse() == false;

            // get the partition iterator corresponding to the
            // current data response
            PartitionIterator pi = UnfilteredPartitionIterators.filter(response.makeIterator(command), command.nowInSec());

            while (pi.hasNext())
            {
                // pi.next() returns a RowIterator
                RowIterator ri = pi.next();

                if (keySpaceName.isEmpty())
                {
                    key = ri.partitionKey();
                    tableMetadata = ri.metadata();
                    keySpaceName = tableMetadata.keyspace;
                    doubleTreasTag.setKey(key);
                    doubleTreasTag.setTableMetadata(tableMetadata);
                    doubleTreasTag.setKeySpace(keySpaceName);

                    TreasTagMap localTagMap = TreasMap.getInternalMap().putIfAbsent(key.toString(), new TreasTagMap());

                    TreasValueID obj = localTagMap.readTag();
                    TreasTag localTag = obj.maxTag;
                    String value = obj.value;
                    quorumMap.put(localTag,1);
                    List<String> codeList = new ArrayList<>();
                    codeList.add(value);
                    decodeMap.put(localTag,codeList);

                    logger.debug(localTag.toString());

                    if (TreasConfig.num_intersect == 1) {
                        quorumTagMax = localTag;
                    }

                    if (TreasConfig.num_recover == 1) {
                        decodeTagMax = localTag;
                        decodeValMax = codeList;
                    }
                }

                while (ri.hasNext())
                {
                    Row row = ri.next();
                    for (Cell c : row.cells())
                    {
                        String colName = c.column().name.toString();

                        // if it is a timeStamp field, we need to check it
                        if (colName.startsWith(TreasConfig.TAG_PREFIX))
                        {
                            try {
                                if (ByteBufferUtil.string(c.value()).isEmpty()) {
                                    continue;
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            TreasTag curTag = TreasTag.deserialize(c.value());

                            logger.debug(curTag.toString());

                            if (quorumMap.containsKey(curTag))
                            {
                                int currentCount = quorumMap.get(curTag) + 1;
                                quorumMap.put(curTag, currentCount);
                                // if has enough k values
                                if (currentCount == TreasConfig.num_intersect)
                                {
                                    if (curTag.isLarger(quorumTagMax))
                                    {
                                        quorumTagMax = curTag;
                                    }
                                }
                            }
                            else
                            {
                                quorumMap.put(curTag, 1);
                                if (TreasConfig.num_intersect == 1)
                                {
                                    if (curTag.isLarger(quorumTagMax))
                                    {
                                        quorumTagMax = curTag;
                                    }
                                }
                            }
                        }
                        // Notice that only one column has the data
                        else if (colName.startsWith(TreasConfig.VAL_PREFIX) && !colName.equals("field0"))
                        {
                            // Fetch the code out
                            String value = "";
                            try
                            {
                                value = ByteBufferUtil.string(c.value());
                            }
                            catch (Exception e)
                            {
                                System.out.println("Cannot parseData");
                            }

                            if (value.isEmpty()) {
                                continue;
                            }

                            logger.debug("The value I get from replica is" + value);

                            // Find the corresponding index to fetch the tag value
                            int index = Integer.parseInt(colName.substring(TreasConfig.VAL_PREFIX.length()));
                            String treasTagColumn = "tag" + index;
                            ColumnIdentifier tagOneIdentifier = new ColumnIdentifier(treasTagColumn, true);
                            ColumnMetadata columnMetadata = ri.metadata().getColumn(tagOneIdentifier);
                            Cell tagCell = row.getCell(columnMetadata);

                            TreasTag treasTag = TreasTag.deserialize(tagCell.value());

                            if (decodeMap.containsKey(treasTag))
                            {

                                decodeMap.get(treasTag).add(value);

                                List<String> codeList = decodeMap.get(treasTag);

                                if (codeList.size() == TreasConfig.num_recover)
                                {
                                    if (treasTag.isLarger(decodeTagMax))
                                    {
                                        decodeTagMax = treasTag;
                                        decodeValMax = codeList;
                                    }
                                }
                            }
                            else
                            {
                                List<String> codelist = new ArrayList<>();
                                codelist.add(value);
                                decodeMap.put(treasTag, codelist);
                                if (TreasConfig.num_recover == 1)
                                {
                                    if (treasTag.isLarger(decodeTagMax))
                                    {
                                        decodeTagMax = treasTag;
                                        decodeValMax = codelist;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        logger.debug("Finish reading Quorum and Decodable");

        System.out.println(quorumTagMax.getTime() + "," + decodeTagMax.getTime());

        logger.debug(quorumTagMax.getTime() + "," + decodeTagMax.getTime());

        // Either one of them is not satisfied stop the procedure;
        if (quorumTagMax.getTime() == -1 || decodeTagMax.getTime() == -1)
        {
            logger.debug("Fail to get enough result");
        }
        else
        {
            logger.debug("Successfully get the result");
            System.out.println("Succesfully get the result");
            doubleTreasTag.getQuorumMaxTreasTag().setWriterId(quorumTagMax.getWriterId());
            doubleTreasTag.getQuorumMaxTreasTag().setLogicalTIme(quorumTagMax.getTime());
            doubleTreasTag.getRecoverMaxTreasTag().setWriterId(decodeTagMax.getWriterId());
            doubleTreasTag.getRecoverMaxTreasTag().setLogicalTIme(decodeTagMax.getTime());
            doubleTreasTag.setCodes(decodeValMax);
        }
    }



    public boolean isDataPresent()
    {
        return dataResponse != null;
    }
}
