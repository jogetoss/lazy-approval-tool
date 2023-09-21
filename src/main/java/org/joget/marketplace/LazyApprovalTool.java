package org.joget.marketplace;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.model.PackageDefinition;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FormService;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;
import org.springframework.context.ApplicationContext;


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
        String wfVariable = (String) map.get("wfVariable");

        String recordId;
        WorkflowAssignment wfAssignment = (WorkflowAssignment) properties.get("workflowAssignment");
        if (wfAssignment != null) {
            recordId = appService.getOriginProcessId(wfAssignment.getProcessId());
        } else {
            recordId = (String)properties.get("recordId");
        }

        String primaryKey = null;

        if (formDefId != null) {
            try {
                primaryKey = appService.getOriginProcessId(wfAssignment.getProcessId());
             
                String assignmentURL = getServerUrl() + "/jw/web/json/app/" + appDef.getId() + "/" + appDef.getVersion().toString()
                + "/plugin/" + getClassName() + "/service?&id=" + primaryKey
                + "&processId=" + processId
                + "&activityDefId=" + activityDefId
                + "&popupFormId=" + formDefId;
                String assignmentURL_approved = assignmentURL + "&" + wfVariable + "=approved";
                String assignmentURL_rejected = assignmentURL + "&" + wfVariable + "=rejected";

                FormRowSet set = new FormRowSet();
                FormRow r1 = new FormRow();
                r1.put("assignment_URL_approved", assignmentURL_approved);
                r1.put("assignment_URL_rejected", assignmentURL_rejected);
                r1.put("wfVariable", wfVariable);
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
        String id = request.getParameter("id");
        String processId = request.getParameter("processId");
        String activityDefId = request.getParameter("activityDefId");
        String popupFormId = request.getParameter("popupFormId");
        String wfVariable = "";

        String appId = request.getParameter("appId");
        String appVersion = request.getParameter("appVersion");
        ApplicationContext ac = AppUtil.getApplicationContext();
        AppService appService = (AppService) ac.getBean("appService");
        WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");
        AppDefinition appDef = appService.getAppDefinition(appId, appVersion);

        WorkflowUserManager workflowUserManager = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");
        FormService formService = (FormService) AppUtil.getApplicationContext().getBean("formService");
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
        }

        FormRow row = new FormRow();
        FormRowSet rowSet = appService.loadFormData(appId, appVersion, popupFormId, id);
        if (rowSet != null && !rowSet.isEmpty()) {
            row = rowSet.get(0);
            wfVariable = row.getProperty("wfVariable");
        }

        String status = request.getParameter(wfVariable);

        if (status != null && !status.isEmpty()) {
            String username = workflowUserManager.getCurrentUsername();
            FormData formData = null;
            Form form = getForm(popupFormId);
            if (form != null && form.getStoreBinder() != null) {
                String msg = "";
                try {
                    WorkflowAssignment assignment = null; // workflowManager.getAssignmentByProcess(processId);
                    Collection<WorkflowAssignment> assignments;

                    assignment = workflowManager.getAssignmentByRecordId(id, packageDef.getId() + "#%#" + processId, activityDefId, username);

                    if (assignment != null) {
                        // matching assignment found

                        // accept assignment
                        if (!assignment.isAccepted()) {
                            workflowManager.assignmentAccept(assignment.getActivityId());
                        }

                        workflowManager.activityVariable(assignment.getActivityId(),wfVariable, status);
                        // complete assignment
                        workflowManager.assignmentComplete(assignment.getActivityId());

                        msg = "Assignment [" + id + "] Completed. You can now close this window.";
                        LogUtil.info(getClassName(), msg);
                    } else {
                        msg = "Assignment [" + id + "] Not Found. You can now close this window.";
                        LogUtil.info(getClassName(), msg);
                    }
                } catch (Exception e) {
                    msg = "Assignment [" + id + "] Failed to Complete. You can now close this window.";
                    LogUtil.error(getClassName(), e, msg);

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

}