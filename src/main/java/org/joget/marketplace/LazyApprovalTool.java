package org.joget.marketplace;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.dao.UserReplacementDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.model.PackageDefinition;
import org.joget.apps.app.model.UserReplacement;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FormService;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.FileManager;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SecurityUtil;
import org.joget.commons.util.StringUtil;
import org.joget.commons.util.UuidGenerator;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.method.P;


public class LazyApprovalTool extends DefaultApplicationPlugin implements PluginWebSupport{
    private final static String MESSAGE_PATH = "messages/LazyApprovalTool";

    @Override
    public String getName() {
        return AppPluginUtil.getMessage("processtool.lazyapprovaltool.name", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getVersion() {
        final Properties projectProp = new Properties();
        try {
            projectProp.load(this.getClass().getClassLoader().getResourceAsStream("project.properties"));
        } catch (IOException ex) {
            LogUtil.error(getClass().getName(), ex, "Unable to get project version from project properties...");
        }
        return projectProp.getProperty("version");
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String getLabel() {
        //support i18n
        return AppPluginUtil.getMessage("processtool.lazyapprovaltool.name", getClassName(), MESSAGE_PATH);
    }
    
    @Override
    public String getDescription() {
        //support i18n
        return AppPluginUtil.getMessage("processtool.lazyapprovaltool.desc", getClassName(), MESSAGE_PATH);
    }
 
    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/LazyApprovalTool.json", null, true, MESSAGE_PATH);
    }

    @Override
    public Object execute(Map map) {
        ApplicationContext ac = AppUtil.getApplicationContext();
        AppService appService = (AppService) ac.getBean("appService");
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();

        String formDefId = (String) map.get("formDefId");
        String processId = (String) map.get("processId");
        String activityDefId = (String) map.get("activityDefId");
        String wfVariableStatus = (String) map.get("wfVariableStatus");
        String assignee = (String) map.get("assignee");
        String showPopupAfterAction = (String) map.get("showPopupAfterAction");
        JSONObject jsonParams = new JSONObject();

        if (showPopupAfterAction == null || showPopupAfterAction.isEmpty()) {
            showPopupAfterAction = "false";
        }
        
        String recordId;
        WorkflowAssignment wfAssignment = (WorkflowAssignment) map.get("workflowAssignment");
        if (wfAssignment != null) {
            recordId = appService.getOriginProcessId(wfAssignment.getProcessId());
        } else {
            recordId = (String)map.get("recordId");
        }

        // get user from participant ID
        if ((assignee != null && assignee.trim().length() != 0)) {
            Collection<String> userList = getUserList(assignee, wfAssignment, appDef);
            for (String user : userList) {
                assignee = user;
            }
        }

        String primaryKey = null;

        if (formDefId != null) {
            try {
                primaryKey = appService.getOriginProcessId(wfAssignment.getProcessId());
             
                jsonParams.put("id", primaryKey);
                jsonParams.put("processId", processId);
                jsonParams.put("activityDefId", activityDefId);
                jsonParams.put("formDefId", formDefId);
                jsonParams.put("assignee", assignee);
                jsonParams.put("showPopupAfterAction", showPopupAfterAction);

                String params = StringUtil.escapeString(SecurityUtil.encrypt(jsonParams.toString()), StringUtil.TYPE_URL, null);

                String assignmentURL = getServerUrl() + "/jw/web/json/app/" + appDef.getId() + "/" + appDef.getVersion().toString() + "/plugin/" + getClassName() + "/service?action=lazyApproval&params=" + params;
                String assignmentURL_approved = assignmentURL + "&" + wfVariableStatus + "=approved";
                String assignmentURL_rejected = assignmentURL + "&" + wfVariableStatus + "=rejected";
              
                FormRowSet set = new FormRowSet();
                FormRow r1 = new FormRow();
                r1.put("assignment_URL_approved", assignmentURL_approved);
                r1.put("assignment_URL_rejected", assignmentURL_rejected);
                r1.put("wfVariableStatus", wfVariableStatus);
                set.add(r1);
                appService.storeFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, set, recordId);
            } catch (Exception ex) {
                LogUtil.error(getClassName(), ex, ex.getMessage());
            }
        }
        return null;
    }

    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        String wfVariableStatus = "";

        String appId = request.getParameter("appId");
        String appVersion = request.getParameter("appVersion");
        ApplicationContext ac = AppUtil.getApplicationContext();
        AppService appService = (AppService) ac.getBean("appService");
        WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");
        AppDefinition appDef = appService.getAppDefinition(appId, appVersion);
        PackageDefinition packageDef = appDef.getPackageDefinition();

        if ("getProcesses".equals(action)) {
            try {
                JSONArray jsonArray = new JSONArray();

                PackageDefinition packageDefinition = appDef.getPackageDefinition();
                Long packageVersion = (packageDefinition != null) ? packageDefinition.getVersion() : new Long(1);
                Collection<WorkflowProcess> processList = workflowManager.getProcessList(appDef.getAppId(), packageVersion.toString());

                Map<String, String> empty = new HashMap<String, String>();
                empty.put("value", "");
                empty.put("label", "");
                jsonArray.put(empty);

                for (WorkflowProcess p : processList) {
                    Map<String, String> option = new HashMap<String, String>();
                    option.put("value", p.getIdWithoutVersion());
                    option.put("label", p.getName() + " (" + p.getIdWithoutVersion() + ")");
                    jsonArray.put(option);
                }

                jsonArray.write(response.getWriter());
            } catch (Exception ex) {
                LogUtil.error(this.getClass().getName(), ex, "Get Process options Error!");
            }
            return;
        } else if ("getActivities".equals(action)) {
            try {
                JSONArray jsonArray = new JSONArray();
                Map<String, String> empty = new HashMap<String, String>();
                empty.put("value", "");
                empty.put("label", "");
                jsonArray.put(empty);

                String processId = request.getParameter("processId");

                if (!"null".equalsIgnoreCase(processId) && !processId.isEmpty()) {
                    String processDefId = "";
                    if (appDef != null) {
                        WorkflowProcess process = appService.getWorkflowProcessForApp(appDef.getId(), appDef.getVersion().toString(), processId);
                        if (process != null) {
                            processDefId = process.getId();
                        }
                    }

                    Collection<WorkflowActivity> activityList = workflowManager.getProcessActivityDefinitionList(processDefId);
                    for (WorkflowActivity a : activityList) {
                        if (!a.getType().equals(WorkflowActivity.TYPE_ROUTE) && !a.getType().equals(WorkflowActivity.TYPE_TOOL)) {
                            Map<String, String> option = new HashMap<String, String>();
                            option.put("value", a.getActivityDefId());
                            option.put("label", a.getName() + " (" + a.getActivityDefId() + ")");
                            jsonArray.put(option);
                        }
                    }
                }

                jsonArray.write(response.getWriter());
            } catch (Exception ex) {
                LogUtil.error(this.getClass().getName(), ex, "Get activity options Error!");
            }
            return;
        } else if ("lazyApproval".equals(action)) {
            String params = SecurityUtil.decrypt(request.getParameter("params"));
            JSONObject jsonParams = new JSONObject(params);
            String id = jsonParams.getString("id");
            String processId = jsonParams.getString("processId");
            String activityDefId = jsonParams.getString("activityDefId");
            String formDefId = jsonParams.getString("formDefId");
            String assignee = jsonParams.getString("assignee");
            String showPopupAfterAction = jsonParams.getString("showPopupAfterAction");

            FormRow row = new FormRow();
            FormRowSet rowSet = appService.loadFormData(appId, appVersion, formDefId, id);
            if (rowSet != null && !rowSet.isEmpty()) {
                row = rowSet.get(0);
                wfVariableStatus = row.getProperty("wfVariableStatus");
            }

        
            String status = request.getParameter(wfVariableStatus);

            if (status != null && !status.isEmpty()) {
                String msg = "";
                try {
                    WorkflowAssignment assignment = null;
                    assignment = workflowManager.getAssignmentByRecordId(id, packageDef.getId() + "#%#" + processId, activityDefId, assignee);

                    if (assignment != null) {
                        workflowManager.activityVariable(assignment.getActivityId(),wfVariableStatus, status);
                        workflowManager.assignmentForceComplete(assignment.getProcessDefId(), assignment.getProcessId(), assignment.getActivityId(), assignee);

                        msg = "Assignment completed. <br><br>Assignment details: <br> Record Id: " + id + "<br> Process Id: " + processId + "<br> Activity Id: " + activityDefId + 
                        "<br> Process Name: " + assignment.getProcessName() + "<br> Activity Name: " + assignment.getActivityName() + "<br><br>You can now close this window.";
                        LogUtil.info(getClassName(), msg);
                    } else {
                        msg = "Assignment not found. <br><br>Assignment details: <br> Record Id: " + id + "<br>Process Id: " + processId + "<br> Activity Id: " + activityDefId 
                        + "<br><br>You can now close this window.";
                        LogUtil.info(getClassName(), msg);
                    }
                } catch (Exception e) {
                    msg = "Assignment failed to complete. <br><br>Assignment details: <br> Record Id: " + id + "<br> Process Id: " + processId + "<br> Activity Id: " + activityDefId 
                    + "<br><br>You can now close this window.";
                    LogUtil.error(getClassName(), e, msg);

                }

                if (showPopupAfterAction.equals("false")){
                    msg += "<script type=\"text/javascript\">";
                    msg += "window.onload = function() { window.close(); };";
                    msg += "</script>";
                }

                response.setContentType("text/html");
                PrintWriter out = response.getWriter();
                out.print(msg);

            }
        }
    }

    public static String getServerUrl() {
        HttpServletRequest request = WorkflowUtil.getHttpServletRequest();
        StringBuffer url = request.getRequestURL();
        URL requestUrl;
        String serverUrl = "";
        try {
            requestUrl = new URL(url.toString());
            serverUrl = requestUrl.getProtocol() + "://" + requestUrl.getHost();
            // Include port if it is present
            int port = requestUrl.getPort();
            if (port != -1) {
                serverUrl += ":" + port;
            }
        } catch (MalformedURLException ex) {
            LogUtil.error("", ex, ex.getMessage());
        }
        return serverUrl;
    }

    public static Form getForm(String formDefId) {
        Form form = null;
        if (formDefId != null && !formDefId.isEmpty()) {
            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            if (appDef != null) {
                FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
                FormService formService = (FormService) AppUtil.getApplicationContext().getBean("formService");
                FormDefinition formDef = formDefinitionDao.loadById(formDefId, appDef);

                if (formDef != null) {
                    String json = formDef.getJson();
                    form = (Form) formService.createElementFromJson(json);
                }
            }
        }
        return form;
    }

    protected FormData getFormData(String json, String recordId, String processId, Form form) {
        try {
            FormData formData = new FormData();
            formData.setPrimaryKeyValue(recordId);
            formData.setProcessId(processId);

            FormRowSet rows = new FormRowSet();
            FormRow row = new FormRow();
            rows.add(row);

            JSONObject jsonObject = new JSONObject(json);
            for(Iterator iterator = jsonObject.keys(); iterator.hasNext();) {
                String key = (String) iterator.next();
                if (FormUtil.PROPERTY_TEMP_REQUEST_PARAMS.equals(key)) {
                    JSONObject tempRequestParamMap = jsonObject.getJSONObject(FormUtil.PROPERTY_TEMP_REQUEST_PARAMS);
                    JSONArray tempRequestParams = tempRequestParamMap.names();
                    if (tempRequestParams != null && tempRequestParams.length() > 0) {
                        for (int l = 0; l < tempRequestParams.length(); l++) {                        
                            List<String> rpValues = new ArrayList<String>();
                            String rpKey = tempRequestParams.getString(l);
                            JSONArray tempValues = tempRequestParamMap.getJSONArray(rpKey);
                            if (tempValues != null && tempValues.length() > 0) {
                                for (int m = 0; m < tempValues.length(); m++) {
                                    rpValues.add(tempValues.getString(m));
                                }
                            }
                            formData.addRequestParameterValues(rpKey, rpValues.toArray(new String[]{}));
                        }
                    }
                } else if (FormUtil.PROPERTY_TEMP_FILE_PATH.equals(key)) {
                    JSONObject tempFileMap = jsonObject.getJSONObject(FormUtil.PROPERTY_TEMP_FILE_PATH);
                    JSONArray tempFiles = tempFileMap.names();
                    if (tempFiles != null && tempFiles.length() > 0) {
                        for (int l = 0; l < tempFiles.length(); l++) {                        
                            List<String> rpValues = new ArrayList<String>();
                            String rpKey = tempFiles.getString(l);
                            JSONArray tempValues = tempFileMap.getJSONArray(rpKey);
                            if (tempValues != null && tempValues.length() > 0) {
                                for (int m = 0; m < tempValues.length(); m++) {
                                    String path = tempValues.getString(m);
                                    File file = FileManager.getFileByPath(path);
                                    if (file != null & file.exists()) {
                                        String newPath = UuidGenerator.getInstance().getUuid() + File.separator + file.getName();
                                        FileUtils.copyFile(file, new File(FileManager.getBaseDirectory(), newPath));
                                        rpValues.add(newPath);
                                    }
                                }
                            }
                            row.putTempFilePath(rpKey, rpValues.toArray(new String[]{}));
                        }
                    }
                } else {
                    String value = jsonObject.getString(key);
                    row.setProperty(key, value);
                }
            }
            row.setId(recordId);
            formData.setStoreBinderData(form.getStoreBinder(), rows);
            return formData;
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, processId);
            return null;
        }
    }

    public static Collection<String> getUserList(String toParticipantId, WorkflowAssignment wfAssignment, AppDefinition appDef) {
        Collection<String> addresses = new HashSet<String>();
        Collection<String> users = new HashSet<String>();

        if (toParticipantId != null && !toParticipantId.isEmpty() && wfAssignment != null) {
            WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");
            WorkflowProcess process = workflowManager.getProcess(wfAssignment.getProcessDefId());
            toParticipantId = toParticipantId.replace(";", ",");
            String pIds[] = toParticipantId.split(",");
            for (String pId : pIds) {
                pId = pId.trim();
                if (pId.length() == 0) {
                    continue;
                }

                Collection<String> userList = null;
                userList = WorkflowUtil.getAssignmentUsers(process.getPackageId(), wfAssignment.getProcessDefId(), wfAssignment.getProcessId(), wfAssignment.getProcessVersion(), wfAssignment.getActivityId(), "", pId.trim());

                if (userList != null && userList.size() > 0) {
                    users.addAll(userList);
                }
            }
            
            //send to replacement user
            if (!users.isEmpty()) {
                Collection<String> userList = new HashSet<String>();
                String args[] = wfAssignment.getProcessDefId().split("#");
                
                for (String u : users) {
                    UserReplacementDao urDao = (UserReplacementDao) AppUtil.getApplicationContext().getBean("userReplacementDao");
                    Collection<UserReplacement> replaces = urDao.getUserTodayReplacedBy(u, args[0], args[2]);
                    if (replaces != null && !replaces.isEmpty()) {
                        for (UserReplacement ur : replaces) {
                            userList.add(ur.getReplacementUser());
                        }
                    }
                }
                
                if (userList.size() > 0) {
                    users.addAll(userList);
                }
            }
        }
        return users;
    }

}