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


package com.yjp.flink.sql.table;

import com.yjp.flink.sql.enums.ETableType;
import com.yjp.flink.sql.parser.CreateTableParser;
import com.yjp.flink.sql.side.SideTableInfo;
import com.yjp.flink.sql.side.StreamSideFactory;
import com.yjp.flink.sql.sink.StreamSinkFactory;
import com.yjp.flink.sql.source.StreamSourceFactory;
import com.yjp.flink.sql.util.MathUtil;
import org.apache.flink.calcite.shaded.com.google.common.base.Strings;
import org.apache.flink.shaded.curator.org.apache.curator.shaded.com.google.common.collect.Maps;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Create table statement parsing table structure to obtain specific information
 * Date: 2018/6/25
 * Company: www.yjp.com
 *
 * @author xuchao
 */

public class TableInfoParser {

    private final static String TYPE_KEY = "type";

    private final static String SIDE_TABLE_SIGN = "(?i)^PERIOD\\s+FOR\\s+SYSTEM_TIME$";

    private final static Pattern SIDE_PATTERN = Pattern.compile(SIDE_TABLE_SIGN);

    private Map<String, AbsTableParser> sourceTableInfoMap = Maps.newConcurrentMap();

    private Map<String, AbsTableParser> targetTableInfoMap = Maps.newConcurrentMap();

    private Map<String, AbsTableParser> sideTableInfoMap = Maps.newConcurrentMap();

    /**
     * Parsing loaded plugin
     */
    public TableInfo parseWithTableType(int tableType, CreateTableParser.SqlParserResult parserResult,
                                        String localPluginRoot) throws Exception {
        AbsTableParser absTableParser = null;
        Map<String, Object> props = parserResult.getPropMap();
        String type = MathUtil.getString(props.get(TYPE_KEY));

        if (Strings.isNullOrEmpty(type)) {
            throw new RuntimeException("create table statement requires property of type");
        }

        if (tableType == ETableType.SOURCE.getType()) {
            //判断是否为维表 维表有  PERIOD FOR SYSTEM_TIME 标识
            boolean isSideTable = checkIsSideTable(parserResult.getFieldsInfoStr());

            if (!isSideTable) {
                absTableParser = sourceTableInfoMap.get(type);
                if (absTableParser == null) {
                    //根据不同的type(如:kafka11)反射不同的子类对象
                    absTableParser = StreamSourceFactory.getSqlParser(type, localPluginRoot);
                    sourceTableInfoMap.put(type, absTableParser);
                }
            } else {
                absTableParser = sideTableInfoMap.get(type);
                if (absTableParser == null) {
                    String cacheType = MathUtil.getString(props.get(SideTableInfo.CACHE_KEY));
                    //根据cacheType 加载全量或者异步 反射不同的子类对象
                    absTableParser = StreamSideFactory.getSqlParser(type, localPluginRoot, cacheType);
                    sideTableInfoMap.put(type, absTableParser);
                }
            }

        } else if (tableType == ETableType.SINK.getType()) {
            absTableParser = targetTableInfoMap.get(type);
            if (absTableParser == null) {
                absTableParser = StreamSinkFactory.getSqlParser(type, localPluginRoot);
                targetTableInfoMap.put(type, absTableParser);
            }
        }

        if (absTableParser == null) {
            throw new RuntimeException(String.format("not support %s type of table", type));
        }

        Map<String, Object> prop = Maps.newHashMap();

        //Shield case   将with中的信息放入prop
        parserResult.getPropMap().forEach((key, val) -> prop.put(key.toLowerCase(), val));
        //不同的子类进入不同的getTableInfo()  主要是为TableInfo赋值
        return absTableParser.getTableInfo(parserResult.getTableName(), parserResult.getFieldsInfoStr(), prop);
    }

    /**
     * judge dim table of PERIOD FOR SYSTEM_TIME
     *
     * @param tableField
     * @return
     */
    private static boolean checkIsSideTable(String tableField) {
        String[] fieldInfos = tableField.split(",");
        for (String field : fieldInfos) {
            Matcher matcher = SIDE_PATTERN.matcher(field.trim());
            if (matcher.find()) {
                return true;
            }
        }

        return false;
    }
}
