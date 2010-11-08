package org.akaza.openclinica.controller;

import org.akaza.openclinica.bean.extract.DatasetBean;
import org.akaza.openclinica.bean.extract.ExtractBean;
import org.akaza.openclinica.bean.extract.ExtractPropertyBean;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
// import org.akaza.openclinica.control.extract.StdScheduler;
import org.akaza.openclinica.control.SpringServletAccess;
import org.akaza.openclinica.dao.core.CoreResources;
import org.akaza.openclinica.dao.extract.DatasetDAO;
import org.akaza.openclinica.dao.managestudy.StudyDAO;

import org.akaza.openclinica.service.extract.XsltTriggerService;
import org.apache.commons.dbcp.BasicDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.akaza.openclinica.web.SQLInitServlet;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.StdScheduler;
import org.springframework.scheduling.quartz.JobDetailBean;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import javax.servlet.http.HttpServletRequest;

@Controller("extractController")
@RequestMapping("/extract")
public class ExtractController {
    @Autowired
    @Qualifier("sidebarInit")
    private SidebarInit sidebarInit;

    @Autowired
    @Qualifier("dataSource")
    private BasicDataSource dataSource;
    
    private DatasetDAO datasetDao;

    private StdScheduler scheduler;

    private static String SCHEDULER = "schedulerFactoryBean";
    
    public ExtractController() {
        
    }
    
    /**
     * process the page from whence you came, i.e. extract a dataset
     * @param id, the id of the extract properties bean, gained from Core Resources
     * @param datasetId, the id of the dataset, found through DatasetDAO
     * @param request, http request
     * @return model map, but more importantly, creates a quartz job which runs right away and generates all output there
     */
    @RequestMapping(method = RequestMethod.GET)
    public ModelMap processSubmit(@RequestParam("id") String id, @RequestParam("datasetId") String datasetId, HttpServletRequest request)  {
        ModelMap map = new ModelMap();
        // String datasetId = (String)request.getAttribute("datasetId");
        // String id = (String)request.getAttribute("id");
        System.out.println("found both id " + id + " and dataset " + datasetId);
        // get extract id
        // get dataset id
        // if id is a number and dataset id is a number ...
        datasetDao = new DatasetDAO(dataSource);
        UserAccountBean userBean = (UserAccountBean) request.getSession().getAttribute("userBean");
        
        ExtractPropertyBean epBean = CoreResources.findExtractPropertyBeanById(new Integer(id).intValue());
        
        DatasetBean dsBean = (DatasetBean)datasetDao.findByPK(new Integer(datasetId).intValue());
        // set the job in motion
        String[] files = epBean.getFileName();
        String exportFileName;
        int fileSize = files.length;
        int  cnt = 0;
        JobDetailBean jobDetailBean = new JobDetailBean();
        SimpleTrigger simpleTrigger = null;
        //TODO: if files and export names size is not same... throw an error

       while(cnt < fileSize)
       {
        
        XsltTriggerService xsltService = new XsltTriggerService();
        
        // TODO get a user bean somehow?
        String generalFileDir = SQLInitServlet.getField("filePath");
        String pattern = "yyyy" + File.separator + "MM" + File.separator + "dd" + File.separator + "HHmmssSSS" + File.separator;
        SimpleDateFormat sdfDir = new SimpleDateFormat(pattern);
        generalFileDir = generalFileDir + "datasets" + File.separator + dsBean.getId() + File.separator + sdfDir.format(new java.util.Date());
        exportFileName = epBean.getExportFileName()[cnt];
        dsBean.setName(dsBean.getName().replaceAll(" ", "_"));
        // need to set the dataset path here, tbh
        System.out.println("found odm xml file path " + generalFileDir);
        // next, can already run jobs, translations, and then add a message to be notified later
        String xsltPath = SQLInitServlet.getField("filePath") + "xslt" + File.separator +files[cnt];
        String endFilePath = epBean.getFileLocation();
        endFilePath  = getEndFilePath(endFilePath,dsBean,sdfDir);
        if(epBean.getPostProcExportName()!=null)
        {
        	//String preProcExportPathName = getEndFilePath(epBean.getPostProcExportName(),dsBean,sdfDir);
        	String preProcExportPathName = resolveExportFilePath(epBean.getPostProcExportName());
        	epBean.setPostProcExportName(preProcExportPathName);
        }
        if(epBean.getPostProcLocation()!=null)
        {
        	String prePocLoc = getEndFilePath(epBean.getPostProcLocation(),dsBean,sdfDir);
        	epBean.setPostProcLocation(prePocLoc);
        }
        
        // also need to add the status fields discussed w/ cc:
        // result code, user message, optional URL, archive message, log file message
        // asdf table: sort most recent at top
        System.out.println("found xslt file name " + xsltPath);
        
        // String xmlFilePath = generalFileDir + ODMXMLFileName;
         simpleTrigger = xsltService.generateXsltTrigger(xsltPath, 
        		 generalFileDir, // xml_file_path
                endFilePath + File.separator, 
                resolveExportFilePath(exportFileName), 
                dsBean.getId(), 
                epBean, userBean, request.getLocale().getLanguage());
        // System.out.println("just set locale: " + request.getLocale().getLanguage());
        scheduler = getScheduler(request);
        
        jobDetailBean = new JobDetailBean();
        jobDetailBean.setGroup(xsltService.TRIGGER_GROUP_NAME);
        jobDetailBean.setName(simpleTrigger.getName());
        jobDetailBean.setJobClass(org.akaza.openclinica.job.XsltStatefulJob.class);
        jobDetailBean.setJobDataMap(simpleTrigger.getJobDataMap());
        jobDetailBean.setDurability(true); // need durability? YES - we will want to see if it's finished
        jobDetailBean.setVolatility(false);
        
        try {
            Date dateStart = scheduler.scheduleJob(jobDetailBean, simpleTrigger);
            System.out.println("== found job date: " + dateStart.toString());
            
        } catch (SchedulerException se) {
            se.printStackTrace();
        }
       cnt++;
       }
        request.setAttribute("datasetId", datasetId);
        // set the job name here in the user's session, so that we can ping the scheduler to pull it out later
        if(jobDetailBean!=null)
        request.getSession().setAttribute("jobName", jobDetailBean.getName());
        if(simpleTrigger!= null)
        request.getSession().setAttribute("groupName", simpleTrigger.getGroup());
       
        request.getSession().setAttribute("datasetId", new Integer(dsBean.getId()));
        return map;
    }
    
    //TODO: ${linkURL} needs to be added
    private String getEndFilePath(String endFilePath,DatasetBean dsBean,SimpleDateFormat sdfDir){
    	 String simpleDatePattern =  "yyyy" + File.separator + "MM" + File.separator + "dd" + File.separator ;
         SimpleDateFormat sdpDir = new SimpleDateFormat(simpleDatePattern);
    	
         
         if(endFilePath.contains("$exportFilePath")) {
             endFilePath = 	endFilePath.replace("$exportFilePath", SQLInitServlet.getField("filePath")+File.separator+"datasets"+File.separator+"transformations");// was + File.separator, tbh
         }
      
          if(endFilePath.contains("${exportFilePath}")) {
             endFilePath = 	endFilePath.replace("${exportFilePath}", SQLInitServlet.getField("filePath")+File.separator+"datasets"+File.separator+"transformations");// was + File.separator, tbh
         }
         if(endFilePath.contains("$datasetId")) {
         	endFilePath = endFilePath.replace("$datasetId", dsBean.getId()+"");
         }
         if(endFilePath.contains("${datasetId}")) {
          	endFilePath = endFilePath.replace("${datasetId}", dsBean.getId()+"");
          }
         if(endFilePath.contains("$datasetName")) {
         	endFilePath = endFilePath.replace("$datasetName", dsBean.getName());
         }
         if(endFilePath.contains("${datasetName}"))
        		 {
        	 endFilePath = endFilePath.replace("${datasetName}", dsBean.getName());
        		 }
         if(endFilePath.contains("$date")) {
         	endFilePath = endFilePath.replace("$date",sdpDir.format(new java.util.Date()) );
         }
         if(endFilePath.contains("${date}"))
         {
        	 endFilePath = endFilePath.replace("${date}",sdpDir.format(new java.util.Date()) );
         }
         if(endFilePath.contains("$datetime")) {
         	endFilePath = endFilePath.replace("$datetime",  sdfDir.format(new java.util.Date()));
         }
         if(endFilePath.contains("${datetime}")){
        		endFilePath = endFilePath.replace("${datetime}",  sdfDir.format(new java.util.Date()));
         }
    	
    	return endFilePath;
    }
    private void setUpSidebar(HttpServletRequest request) {
        if (sidebarInit.getAlertsBoxSetup() == SidebarEnumConstants.OPENALERTS) {
            request.setAttribute("alertsBoxSetup", true);
        }

        if (sidebarInit.getInfoBoxSetup() == SidebarEnumConstants.OPENINFO) {
            request.setAttribute("infoBoxSetup", true);
        }
        if (sidebarInit.getInstructionsBoxSetup() == SidebarEnumConstants.OPENINSTRUCTIONS) {
            request.setAttribute("instructionsBoxSetup", true);
        }

        if (!(sidebarInit.getEnableIconsBoxSetup() == SidebarEnumConstants.DISABLEICONS)) {
            request.setAttribute("enableIconsBoxSetup", true);
        }
    }

    public SidebarInit getSidebarInit() {
        return sidebarInit;
    }

    public void setSidebarInit(SidebarInit sidebarInit) {
        this.sidebarInit = sidebarInit;
    }
    
    private StdScheduler getScheduler(HttpServletRequest request) {
        scheduler = this.scheduler != null ? scheduler : (StdScheduler) SpringServletAccess.getApplicationContext(request.getSession().getServletContext()).getBean(SCHEDULER);
        return scheduler;
    }
    
    private String resolveExportFilePath(String  epBeanFileName) {
        // String retMe = "";
        //String epBeanFileName = epBean.getExportFileName();
        // important that this goes first, tbh
        if (epBeanFileName.contains("$datetime")) {
            String dateTimeFilePattern = "yyyy-MM-dd-HHmmssSSS";
            SimpleDateFormat sdfDir = new SimpleDateFormat(dateTimeFilePattern);
            epBeanFileName = epBeanFileName.replace("$datetime", sdfDir.format(new java.util.Date()));
        } else if (epBeanFileName.contains("$date")) {
            String dateFilePattern = "yyyy-MM-dd";
            SimpleDateFormat sdfDir = new SimpleDateFormat(dateFilePattern);
            epBeanFileName = epBeanFileName.replace("$date", sdfDir.format(new java.util.Date()));
            // sdfDir.format(new java.util.Date())
            // retMe = epBean.getFileLocation() + File.separator + epBean.getExportFileName() + "." + epBean.getPostProcessing().getFileType();
        } else {
            // retMe = epBean.getFileLocation() + File.separator + epBean.getExportFileName() + "." + epBean.getPostProcessing().getFileType();
        }
        return epBeanFileName;// + "." + epBean.getPostProcessing().getFileType();// not really the case - might be text to pdf
        // return retMe;
    }
}
