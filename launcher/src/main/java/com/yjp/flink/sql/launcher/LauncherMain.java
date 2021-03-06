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


package com.yjp.flink.sql.launcher;

import avro.shaded.com.google.common.collect.Lists;
import com.alibaba.fastjson.JSON;
import com.yjp.flink.sql.ClusterMode;
import com.yjp.flink.sql.Main;
import com.yjp.flink.sql.launcher.perjob.PerJobSubmitter;
import com.yjp.flink.sql.options.LauncherOptionParser;
import com.yjp.flink.sql.options.LauncherOptions;
import com.yjp.flink.sql.util.PluginUtil;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.PackagedProgramUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;

import java.io.*;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Date: 2017/2/20
 * Company: www.yjp.com
 *
 * @author xuchao
 */

public class LauncherMain {
    private static final String CORE_JAR = "core";

    private static String SP = File.separator;


    private static String getLocalCoreJarPath(String localSqlRootJar) throws Exception {
        String jarPath = PluginUtil.getCoreJarFileName(localSqlRootJar, CORE_JAR);
        return localSqlRootJar + SP + jarPath;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].endsWith(".json")) {
            //读取json文件内容转换为数组信息
            args = parseJson(args);
        }
        //解析参数 转换为LauncherOptions对象 POJO
        LauncherOptionParser optionParser = new LauncherOptionParser(args);
        LauncherOptions launcherOptions = optionParser.getLauncherOptions();
        String mode = launcherOptions.getMode();
        //将flinkconf，yarnconf，flinkJarPath抛弃  其他的装入List  key前面多-
        List<String> argList = optionParser.getProgramExeArgList();

        if (mode.equals(ClusterMode.local.name())) {
            String[] localArgs = argList.toArray(new String[argList.size()]);
            Main.main(localArgs);
            return;
        }

        String pluginRoot = launcherOptions.getLocalSqlPluginPath();
        File jarFile = new File(getLocalCoreJarPath(pluginRoot));
        String[] remoteArgs = argList.toArray(new String[argList.size()]);
        //最终还是调用com.yjp.flink.sql.Main
        PackagedProgram program = new PackagedProgram(jarFile, Lists.newArrayList(), remoteArgs);

        if (StringUtils.isNotBlank(launcherOptions.getSavePointPath())) {
            program.setSavepointRestoreSettings(SavepointRestoreSettings.forPath(launcherOptions.getSavePointPath(), BooleanUtils.toBoolean(launcherOptions.getAllowNonRestoredState())));
        }

        if (mode.equals(ClusterMode.yarnPer.name())) {
            String flinkConfDir = launcherOptions.getFlinkconf();
            Configuration config = GlobalConfiguration.loadConfiguration(flinkConfDir);
            JobGraph jobGraph = PackagedProgramUtils.createJobGraph(program, config, 1);
            PerJobSubmitter.submit(launcherOptions, jobGraph);
        } else {
            ClusterClient clusterClient = ClusterClientFactory.createClusterClient(launcherOptions);
            clusterClient.run(program, 1);
            clusterClient.shutdown();
        }

        System.out.println("---submit end----");
    }

    private static String[] parseJson(String[] args) {
        BufferedReader reader = null;
        StringBuilder lastStr = new StringBuilder();
        try {
            FileInputStream fileInputStream = new FileInputStream(args[0]);
            InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream, "UTF-8");
            reader = new BufferedReader(inputStreamReader);
            String tempString = null;
            while ((tempString = reader.readLine()) != null) {
                lastStr.append(tempString);
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        Map<String, Object> map = JSON.parseObject(lastStr.toString(), new com.alibaba.fastjson.TypeReference<Map<String, Object>>() {
        });
        List<String> list = new LinkedList<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            list.add("-" + entry.getKey());
            list.add(entry.getValue().toString());
        }
        return list.toArray(new String[list.size()]);
    }
}
