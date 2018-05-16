package com.dfire.core.util;

import com.dfire.common.enums.JobRunType;
import com.dfire.common.constants.RunningJobKeys;
import com.dfire.common.entity.HeraDebugHistory;
import com.dfire.common.entity.HeraFile;
import com.dfire.common.entity.model.HeraJobBean;
import com.dfire.common.entity.vo.HeraJobHistoryVo;
import com.dfire.common.entity.vo.HeraProfileVo;
import com.dfire.common.processor.DownProcessor;
import com.dfire.common.processor.JobProcessor;
import com.dfire.common.processor.Processor;
import com.dfire.common.service.HeraFileService;
import com.dfire.common.service.HeraGroupService;
import com.dfire.common.service.HeraProfileService;
import com.dfire.common.util.DateUtil;
import com.dfire.common.util.HierarchyProperties;
import com.dfire.common.util.RenderHierarchyProperties;
import com.dfire.core.job.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 上午12:13 2018/4/26
 * @desc
 */
@Slf4j
public class JobUtils {

    public static final Pattern pattern = Pattern.compile("download\\[(doc|hdfs|http)://.+]");

    public static Job createDebugJob(JobContext jobContext, HeraDebugHistory heraDebugHistory,
                                     String workDir, ApplicationContext applicationContext) {
        jobContext.setDebugHistory(heraDebugHistory);
        jobContext.setWorkDir(workDir);
        //脚本中的变量替换，暂时不做
        HierarchyProperties hierarchyProperties = new HierarchyProperties(new HashMap<>());
        String script = heraDebugHistory.getScript();
        List<Map<String, String>> resources = new ArrayList<>();
        script = resolveScriptResource(resources, script, applicationContext);
        jobContext.setResources(resources);
        hierarchyProperties.setProperty("job.script", script);
        //权限控制判断，暂时不做
        HeraFileService heraFileService = (HeraFileService) applicationContext.getBean("heraFileService");
        String owner = heraFileService.getHeraFile(heraDebugHistory.getId()).getOwner();
        HeraProfileService heraProfileService = (HeraProfileService) applicationContext.getBean("profileService");
        HeraProfileVo heraProfile = heraProfileService.findByOwner(owner);
        if (heraProfile != null && heraProfile.getHadoopConf() != null) {
            for (String key : heraProfile.getHadoopConf().keySet())
                hierarchyProperties.setProperty(key, heraProfile.getHadoopConf().get(key));
        }

        jobContext.setProperties(new RenderHierarchyProperties(hierarchyProperties));
        hierarchyProperties.setProperty("hadoop.mappred.job.hera_id", "hera_debug_" + heraDebugHistory.getId());

        List<Job> pres = new ArrayList<>(1);
        pres.add(new DownLoadJob(jobContext));
        Job core = null;
        if (heraDebugHistory.getRunType().equalsIgnoreCase(JobRunType.Shell.toString())) {
            core = new ShellJob(jobContext);
        } else if (heraDebugHistory.getRunType().equalsIgnoreCase(JobRunType.Hive.toString())) {
            core = new HadoopShellJob(jobContext);
        }
        Job job = new WithProcessJob(jobContext, pres, new ArrayList<>(), core, applicationContext);
        return job;
    }

    public static Job createJob(JobContext jobContext, HeraJobBean jobBean, HeraJobHistoryVo history, String workDir, ApplicationContext applicationContext) {
        jobContext.setHeraJobHistory(history);
        jobContext.setWorkDir(workDir);
        HierarchyProperties hierarchyProperties = jobBean.getHierarchyProperties();
        Map<String, String> configs = history.getProperties();
        if (configs != null && !configs.isEmpty()) {
            history.getLog().appendHera("this job has configs");
            for (String key : configs.keySet()) {
                hierarchyProperties.setProperty(key, configs.get(key));
                history.getLog().appendHera(key + "=" + configs.get(key));
            }
        }
        jobContext.setProperties(new RenderHierarchyProperties(hierarchyProperties));
        List<Map<String, String>> resource = jobBean.getHierarchyResources();
        HeraGroupService heraGroupService = (HeraGroupService) applicationContext.getBean("heraGroupService");
        int jobId = jobBean.getHeraJobVo().getId();
        String script = heraGroupService.getHeraJobVo(jobId).getSource().getScript();
        String actionDate = history.getId().substring(0, 12) + "00";
        if (StringUtils.isNotBlank(actionDate) && actionDate.length() == 14) {
            script = RenderHierarchyProperties.render(script, actionDate);
        }
        if (jobBean.getHeraJobVo().getRunType().equals(JobRunType.Shell)
                || jobBean.getHeraJobVo().getRunType().equals(JobRunType.Hive)) {
            script = resolveScriptResource(resource, script, applicationContext);
        }
        jobContext.setResources(resource);
        if (StringUtils.isNotBlank(actionDate) && actionDate.length() == 14) {
            script = replace(jobContext.getProperties().getAllProperties(actionDate), script);
        } else {
            script = replace(jobContext.getProperties().getAllProperties(), script);
        }

        script = replaceScript(history, script);
        hierarchyProperties.setProperty(RunningJobKeys.JOB_SCRIPT, script);

        List<Job> pres = parseJobs(jobContext, applicationContext, jobBean,
                jobBean.getHeraJobVo().getPreProcessors(), history, workDir);

        List<Job> posts = parseJobs(jobContext, applicationContext, jobBean,
                jobBean.getHeraJobVo().getPostProcessors(), history, workDir);

        Job core = null;
        if (jobBean.getHeraJobVo().getRunType() == JobRunType.Shell) {
            core = new HadoopShellJob(jobContext);
        } else if (jobBean.getHeraJobVo().getRunType() == JobRunType.Hive) {
            core = new HiveJob(jobContext, applicationContext);
        }
        Job job = new WithProcessJob(jobContext, pres, posts, core, applicationContext);
        return job;

    }

    private static List<Job> parseJobs(JobContext jobContext, ApplicationContext applicationContext, HeraJobBean jobBean,
                                       List<Processor> processors, HeraJobHistoryVo history, String workDir) {
        List<Job> jobs = new ArrayList<>();
        Map<String, String> map = jobContext.getProperties().getAllProperties();
        Map<String, String> varMap = new HashMap<>();
        try {
            for (String key : map.keySet()) {
                String value = map.get(key);

                if (StringUtils.isBlank(value)) {
                    if (StringUtils.isBlank(history.getStatisticsEndTime()) || StringUtils.isBlank(history.getTimezone())) {
                        value = value.replace("${j_set}", history.getStatisticsEndTime());
                        value = value.replace("${j_est}", DateUtil.string2Timestamp(history.getStatisticsEndTime(), history.getTimezone()) / 1000 + "");
                        varMap.put(key, value);
                    }
                }
            }
        } catch (ParseException e) {
            log.error("parse end time error");
        }
        for(Processor processor : processors) {
            String config = processor.getConfig();
            if(StringUtils.isNotBlank(config)) {
                for(String key : map.keySet()) {
                    String old = "";
                    do {
                        old = config;
                        String value = varMap.get(key).replace("\"", "\\\"");
                        config = config.replace(key, value);

                    } while (!old.equals(config));
                }
                processor.parse(config);
            }
            if(processor instanceof DownProcessor) {
                jobs.add(new DownLoadJob(jobContext));
            } else if(processor instanceof JobProcessor) {
                Integer depth = (Integer) jobContext.getData("depth");
                if(depth == null) {
                    depth = 0;
                }
                if(depth < 2) {
                    JobProcessor jobProcessor = (JobProcessor) processor;
                    Map<String, String> configs = jobProcessor.getKvConfig();
                    for(String key : configs.keySet()) {
                        if(configs.get(key) != null) {
                            jobBean.getHeraJobVo().getConfigs().put(key, map.get(key));
                        }
                    }
                    File directory = new File(workDir + File.separator + "job-processor-" + jobProcessor.getJobId());
                    if(!directory.exists()) {
                        directory.mkdir();
                    }
                    JobContext subJobContext = new JobContext(jobContext.getRunType());
                    subJobContext.putData("depth", ++ depth);
                    Job job = createJob(subJobContext, jobBean, history, directory.getAbsolutePath(), applicationContext);
                    jobs.add(job);
                } else {
                    jobContext.getHeraJobHistory().getLog().appendHera("递归的JobProcessor处理单元深度过大，停止递归");
                }
            }

        }
        return jobs;
    }

    private static String replaceScript(HeraJobHistoryVo history, String script) {
        if (StringUtils.isBlank(history.getStatisticsEndTime()) || StringUtils.isBlank(history.getTimezone())) {
            return script;
        }
        script = script.replace("${j_set}", history.getStatisticsEndTime());
        try {
            script = script.replace("${j_est}", DateUtil.string2Timestamp(history.getStatisticsEndTime(),
                    history.getTimezone()) / 1000 + "");
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return script;
    }

    private static String replace(Map<String, String> allProperties, String script) {
        if (script == null) {
            return null;
        }
        Map<String, String> varMap = new HashMap<String, String>();
        for (String key : varMap.keySet()) {
            if (varMap.get(key) != null) {
                varMap.put("${" + key + "}", varMap.get(key));
            }
        }
        for (String key : varMap.keySet()) {
            String old = "";
            do {
                old = script;
                script = script.replace(key, varMap.get(key));
            } while (!old.equals(script));
        }
        return script;
    }

    private static String resolveScriptResource(List<Map<String, String>> resources, String script, ApplicationContext applicationContext) {
        Matcher matcher = pattern.matcher(script);
        while (matcher.find()) {
            String group = matcher.group();
            group = group.substring(group.indexOf("[") + 1, group.indexOf("]"));
            String[] url = StringUtils.split(group, ".");
            String uri = url[0];
            String name = "";
            String referScript = null;
            String path = uri.substring(uri.lastIndexOf('/') + 1);
            Map<String, String> map = new HashMap<>(2);
            if (uri.startsWith("doc://")) {
                HeraFileService fileService = (HeraFileService) applicationContext.getBean("fileService");
                HeraFile heraFile = fileService.getHeraFile(path);
                name = heraFile.getName();
                referScript = heraFile.getContent();
            }

            if (url.length > 1) {
                name = "";
                for (int i = 0; i < url.length; i++) {
                    if (i > 1) {
                        name += "_";
                    }
                    name += url[i];
                }
            } else if (url.length == 1) {
                if (uri.startsWith("hdfs://")) {
                    if (uri.endsWith("/")) {
                        continue;
                    }
                    name = path;
                }
            }
            boolean exist = false;
            for (Map<String, String> env : resources) {
                if (env.get("name").equals(name)) {
                    exist = true;
                    break;
                }
            }
            if (!exist) {
                map.put("uri", uri);
                map.put("name", name);
                resources.add(map);
                if (uri.startsWith("doc://") && referScript != null) {
                    map.put("hera-doc-" + path, resolveScriptResource(resources, referScript, applicationContext));
                }
            }
        }
        script = matcher.replaceAll("");
        return script;
    }


}
