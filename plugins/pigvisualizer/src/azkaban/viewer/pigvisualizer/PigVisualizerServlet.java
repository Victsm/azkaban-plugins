/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.viewer.pigvisualizer;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutorManagerAdapter;
import azkaban.executor.ExecutorManagerException;
import azkaban.project.Project;
import azkaban.project.ProjectManager;
import azkaban.user.Permission;
import azkaban.user.Permission.Type;
import azkaban.user.User;
import azkaban.utils.Props;
import azkaban.utils.JSONUtils;
import azkaban.webapp.AzkabanWebServer;
import azkaban.webapp.servlet.LoginAbstractAzkabanServlet;
import azkaban.webapp.servlet.Page;
import azkaban.webapp.session.Session;

public class PigVisualizerServlet extends LoginAbstractAzkabanServlet {
	private static final String PROXY_USER_SESSION_KEY = 
			"hdfs.browser.proxy.user";
	private static final String HADOOP_SECURITY_MANAGER_CLASS_PARAM = 
			"hadoop.security.manager.class";
	private static Logger logger = Logger.getLogger(PigVisualizerServlet.class);

	private Props props;
	private File webResourcesPath;

	private String viewerName;
	private String viewerPath;

	private ExecutorManagerAdapter executorManager;
	private ProjectManager projectManager;

	public PigVisualizerServlet(Props props) {
		this.props = props;
		viewerName = props.getString("viewer.name");
		viewerPath = props.getString("viewer.path");

		webResourcesPath = new File(new File(props.getSource()).getParentFile().getParentFile(), "web");
		webResourcesPath.mkdirs();
		setResourceDirectory(webResourcesPath);
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		AzkabanWebServer server = (AzkabanWebServer) getApplication();
		executorManager = server.getExecutorManager();
		projectManager = server.getProjectManager();

	}

  private void handleAllExecutions(HttpServletRequest request,
      HttpServletResponse response, Session session)
      throws ServletException, IOException {

		Page page = newPage(request, response, session, 
				"azkaban/viewer/pigvisualizer/allexecutions.vm");
		page.add("viewerPath", viewerPath);
		page.add("viewerName", viewerName);

		page.render();
  }

	private Project getProjectByPermission(int projectId, User user,
			Permission.Type type) {
		Project project = projectManager.getProject(projectId);
		if (project == null) {
			return null;
		}
		if (!hasPermission(project, user, type)) {
			return null;
		}
		return project;
	}

  private void handleVisualizer(HttpServletRequest request,
      HttpServletResponse response, Session session)
      throws ServletException, IOException {

		Page page = newPage(request, response, session, 
				"azkaban/viewer/pigvisualizer/visualizer.vm");
		page.add("viewerPath", viewerPath);
		page.add("viewerName", viewerName);

		int execId = Integer.parseInt(getParam(request, "execid"));
		String jobId = getParam(request, "jobid");
	
		ExecutableFlow exFlow = null;
		Project project = null;
		try {
			exFlow = executorManager.getExecutableFlow(execId);
			project = getProjectByPermission(
					exFlow.getProjectId(), session.getUser(), Type.READ);
		}
		catch (ExecutorManagerException e) {
			page.add("errorMsg", "Error getting project '" + e.getMessage() + "'");
			page.render();
			return;
		}

		if (project == null) {
			page.add("errorMsg", "Error getting project " + exFlow.getProjectId());
			page.render();
			return;
		}
		page.add("projectName", project.getName());
		page.add("execId", execId);
		page.add("jobId", jobId);
		page.add("flowId", exFlow.getFlowId());
		page.render();
	}
	
	private Map<String, JobDagNode> getDagNodeJobNameMap(String jsonDir) 
			throws Exception {
		String outputDagNodeNameFile = jsonDir + "-dagnodemap.json";
		File dagNodeNameMapFile = new File(outputDagNodeNameFile);
		Map<String, Object> jsonObj = (HashMap<String, Object>) 
			  JSONUtils.parseJSONFromFile(dagNodeNameMapFile);
		Map<String, JobDagNode> dagNodeJobNameMap =
				new HashMap<String, JobDagNode>();
		for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
			dagNodeJobNameMap.put(entry.getKey(),
					JobDagNode.fromJson(entry.getValue()));
		}
		return dagNodeJobNameMap;
	}

	private void ajaxFetchJobDag(HttpServletRequest request,
			HttpServletResponse response, HashMap<String, Object> ret, User user,
			ExecutableFlow exFlow) throws ServletException {

		int execId = getIntParam(request, "execid");
		String jobId = getParam(request, "jobid");
		
		String jsonDir = "./executions/" + execId + "/" + jobId;
		Map<String, JobDagNode> dagNodeNameMap = null;
		try {
			dagNodeNameMap = getDagNodeJobNameMap(jsonDir);
		}
		catch (Exception e) {
			ret.put("error", "Error parsing JSON file: " + e.getMessage());
			return;
		}

		ArrayList<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
		ArrayList<Map<String, Object>> edgeList = new ArrayList<Map<String, Object>>();
		for (Map.Entry<String, JobDagNode> entry : dagNodeNameMap.entrySet()) {
			JobDagNode node = entry.getValue();
			HashMap<String, Object> nodeObj = new HashMap<String, Object>();
			nodeObj.put("id", node.getJobId());
			nodeObj.put("level", "0");
			nodeObj.put("type", "pig");
			nodeList.add(nodeObj);

			// Add edges.
			for (String successor : node.getSuccessors()) {
				HashMap<String, Object> edgeObj = new HashMap<String, Object>();
				JobDagNode targetNode = dagNodeNameMap.get(successor);
				if (targetNode == null) {
					ret.put("error", "Node " + successor + " not found.");
					return;
				}
				edgeObj.put("from", node.getJobId());
				edgeObj.put("target", targetNode.getJobId());
				edgeList.add(edgeObj);
			}
		}

		ret.put("nodes", nodeList);
		ret.put("edges", edgeList);
	}

	private void handleAjaxAction(HttpServletRequest request,
			HttpServletResponse response, Session session) 
			throws ServletException, IOException {
		HashMap<String, Object> ret = new HashMap<String, Object>();
		String ajaxName = getParam(request, "ajax");
		if (hasParam(request, "execid")) {
			int execId = getIntParam(request, "execid");
			ExecutableFlow exFlow = null;
			try {
				exFlow = executorManager.getExecutableFlow(execId);
			}
			catch (ExecutorManagerException e) {
				ret.put("error", "Error fetching execution '" + execId + "': " +
						e.getMessage());
			}

			if (exFlow == null) {
				ret.put("error", "Cannot find execution '" + execId + "'");
			} else {
				if (ajaxName.equals("fetchjobdag")) {
					ajaxFetchJobDag(request, response, ret, session.getUser(), exFlow);
				}
			}
		}

		if (ret != null) {
			this.writeJSON(response, ret);
		}
	}
		
  @Override
	protected void handleGet(HttpServletRequest request, 
			HttpServletResponse response, Session session)
			throws ServletException, IOException {
		if (hasParam(request, "ajax")) {
			handleAjaxAction(request, response, session);
		}
		else if (hasParam(request, "execid") && hasParam(request, "jobid")) {
      handleVisualizer(request, response, session);
		}
		else {
      handleAllExecutions(request, response, session);
    }
	}

	@Override
	protected void handlePost(HttpServletRequest request,
			HttpServletResponse response, Session session)
			throws ServletException, IOException {
		if (hasParam(request, "ajax")) {
			handleAjaxAction(request, response, session);
		}
	}
}
