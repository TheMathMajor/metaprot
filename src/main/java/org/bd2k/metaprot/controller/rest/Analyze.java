package org.bd2k.metaprot.controller.rest;

import org.apache.log4j.Logger;
import org.bd2k.metaprot.aws.S3Client;
import org.bd2k.metaprot.aws.S3Status;
import org.bd2k.metaprot.data.FeedBackType;
import org.bd2k.metaprot.data.IntegrityChecker;
import org.bd2k.metaprot.data.siteTrafficData;
import org.bd2k.metaprot.dbaccess.DAOImpl;
import org.bd2k.metaprot.exception.BadRequestException;
import org.bd2k.metaprot.exception.ServerException;
import org.bd2k.metaprot.model.*;
import org.bd2k.metaprot.scheduler.TaskScheduler;
import org.bd2k.metaprot.util.FileAccess;
import org.bd2k.metaprot.util.Globals;
import org.bd2k.metaprot.util.RManager;
import org.json.simple.JSONObject;
import org.rosuda.REngine.REXP;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

/**
 * REST controller that exposes endpoints
 * related to analyzing files.
 *
 * Created by allengong on 8/12/16.
 */
@RestController
@RequestMapping("/analyze")
@DependsOn({"Globals"})
public class Analyze {

    private static final Logger log = Logger.getLogger(Analyze.class);

    @Autowired
    private S3Client s3Client;

    @Autowired
    private DAOImpl dao;

    // for path construction
    private String root = Globals.getPathRoot();
    private String sep = Globals.getPathSeparator();
    private String rScriptLoc = Globals.getrScriptLocation();

    // "/ssd2/metaprot"
    private final String LOCAL_FILE_DOWNLOAD_PATH = root + "ssd2" + sep + "metaprot";

    // "src/main/resources/R/scripts/r_sample_code.R"
    private final String METABOLITES_R_SCRIPT_LOC = rScriptLoc + "r_sample_code.R";
    private final String TEMPORAL_PATTERNS_R_SCRIPT_LOC = rScriptLoc + "scatter_plot_cluster.R";

    // handler to perform R related logic
    private RManager manager = null;

    //Site Traffic Data Manager
    private siteTrafficData trafficData = new siteTrafficData();

    //File Information Data Store
    private HashMap<String, String> fileInfoMap = new HashMap<>();

    /**
     * Analyzes an uploaded CSV file for metabolite analysis.
     *
     * Maps to:
     *
     * HTTP POST /analyze/metabolites/{token}
     *
     * @param token uuid generated by HTTP GET /analyze/token
     * @param key AWS S3 key pointing to the uploaded file
     * @param pThreshold p value threshold
     * @param fcThreshold fold change threshold
     *
     * @return an HTML formatted message ready to be displayed to the end user.
     */
    @RequestMapping(value = "/metabolites/{token}", method = RequestMethod.POST)
    public String analyzeMetabolites(@PathVariable("token") String token,
                                     @RequestParam("objectKey") String key,
                                     @RequestParam("pThreshold") double pThreshold,
                                     @RequestParam("fcThreshold") double fcThreshold) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                pThreshold < 0 ||
                fcThreshold < 0 ||
                !(keyArr[keyArr.length-2].equals(token)) ||
                keyArr.length != 3) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, LOCAL_FILE_DOWNLOAD_PATH + sep + token);
        int status = s3Status.getStatusCode();

        log.info("new status s3: " + s3Status.toString());

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        // everything is OK on the server end, attempt to analyze the file
        File rScript;
        try {
            // generate TaskInfo and queue task for scheduler
            TaskInfo taskInfo = new TaskInfo(token, keyArr[keyArr.length-1], s3Status.getFileSize());
            TaskScheduler scheduler = TaskScheduler.getInstance();
            int portUsed = scheduler.scheduleTask(taskInfo);

            log.info("Port used for Rserve: " + portUsed);

            // run the R commands
            manager = RManager.getInstance(portUsed);
            rScript = new File(METABOLITES_R_SCRIPT_LOC);
            String str = rScript.getAbsolutePath().replace("\\","\\\\");        // affects window env only

            manager.runRScript(str);        // (re) initializes R environment
            manager.runRCommand("analyze.file('" + LOCAL_FILE_DOWNLOAD_PATH + sep + token
                    + sep + keyArr[keyArr.length-1] + "', '" + LOCAL_FILE_DOWNLOAD_PATH + sep +
                    token + sep + "data.csv', '" + LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + "volcano.png', " +
                    pThreshold + ", " + fcThreshold + ")");

            // tell scheduler that all R commands have completed
            scheduler.endTask(portUsed);
            manager.closeConnection();
        } catch (Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        // store results to database, TODO any new logic to read in all result files, for now just one, maybe just need to modify the file access function to return a list of lists
        List<List<MetaboliteStat>> totalResults = new ArrayList<>();
        List<MetaboliteStat> results = new FileAccess().getMetaboliteAnalysisResults(token);
        totalResults.add(results);

        Task currentTask = new Task(token, new Date(), keyArr[keyArr.length-1], pThreshold, fcThreshold, 0);

        // save the chunks
        int numChunks = dao.saveTaskResults(currentTask, totalResults);

        if (numChunks < 0) {
            throw new ServerException("There was an issue with uploading your file, please try again at a later time.");
        }

        // save the task
        currentTask.setNumChunks(numChunks);
        boolean taskSaved = dao.saveTask(currentTask);

        if (!taskSaved) {
            throw new BadRequestException("There was an issue with your task token. Please try again at a later time.");
        }

        // analysis complete and results recorded, safe to delete all temporary files
        new FileAccess().deleteTemporaryAnalysisFiles(token);

        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a href='/metabolite-analysis/results/" + token + "'>results</a>");
    }

    /**
     * Analyzes an uploaded CSV file for pattern recognition analysis.
     *
     * Maps to:
     *
     * HTTP POST /analyze/pattern/{token}
     *
     * @param token uuid generated by HTTP GET /analyze/token
     * @param key AWS S3 key pointing to the uploaded file
     *
     * @return an HTML formatted message ready to be displayed to the end user.
     */
    @RequestMapping(value = "/pattern/{token}", method = RequestMethod.POST)
    public String analyzePatterns(@PathVariable("token") String token,
                                  @RequestParam("objectKey") String key,
                                  @RequestParam("numClusters") int numClusters,
                                  @RequestParam("minMembersPerCluster") int minMembersPerCluster) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                !(keyArr[keyArr.length-2].equals(token)) ||
                keyArr.length != 3 ||
                numClusters < 1 ||
                minMembersPerCluster < 1) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again.");
        }

        // grab uploaded file from S3
        S3Status s3Status = s3Client.pullAndStoreObject(key, LOCAL_FILE_DOWNLOAD_PATH + sep + token);
        int status = s3Status.getStatusCode();

        log.info("new status s3: " + s3Status.toString());

        // check for errors in s3 pull/store
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        // try to analyze the file with R
        File rScript;
        double[][] regressionLines = null;
        try {
            TaskInfo taskInfo = new TaskInfo(token, keyArr[keyArr.length-1], s3Status.getFileSize());
            TaskScheduler scheduler = TaskScheduler.getInstance();
            int portToUse = scheduler.scheduleTask(taskInfo);

            log.info("Port to use for Rserve: " + portToUse);

            // get manager instance and run R commands
            RManager manager = RManager.getInstance(portToUse);
            rScript = new File(TEMPORAL_PATTERNS_R_SCRIPT_LOC);
            String absScriptPath = rScript.getAbsolutePath().replace("\\","\\\\");        // affects window env only

            manager.runRScript(absScriptPath);          // source the R script
            REXP rexp = manager.runRCommand("analyze.temporal.patterns('" + LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep +
            keyArr[keyArr.length - 1] + "', '" + LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + "clustered_result.csv', " +
                    numClusters + ", " + minMembersPerCluster + ")");

            regressionLines = rexp.asDoubleMatrix();

            scheduler.endTask(portToUse);   // notify scheduler that task is complete (all R commands done)
            manager.closeConnection();
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        // open result file and obtain results of R script analysis
        List<List<PatternRecogStat>> results = new FileAccess().getPatternRecogResults(token);

        // create task object and upload task results to DB (as DynamoDB chunks)
        PatternRecogTask task = new PatternRecogTask(token, new Date(), keyArr[keyArr.length-1], s3Status.getFileSize(),
                numClusters, minMembersPerCluster, 0, regressionLines);

        int numChunks = dao.saveTaskResults(task, results);

        if (numChunks <= 0) {
            throw new ServerException("There was an issue with uploading your file, please try again at a later time.");
        }

        // now save task information to DB
        task.setNumChunks(numChunks);
        boolean taskSaved = dao.saveTask(task);

        if (!taskSaved) {
            throw new BadRequestException("There was an issue with saving your task. Please try again at a later time.");
        }


        // analysis complete and results recorded, safe to delete all temporary files
        //new FileAccess().deleteTemporaryAnalysisFiles(token);

        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a href='/temporal-pattern-recognition/results/" + token + "'>results</a>");
    }

    /**
     * Performs analyzes on a previously uploaded CSV file for pattern recog analysis.
     * If the file/directory no longer exists locally (i.e. expired), then an error is thrown.
     * Instead of returning an HTML response, the results of the analysis are returned in JSON.
     *
     * Maps to:
     *
     * HTTP GET /analyze/pattern/re-analyze/{token}?numClusters={int}
     *
     * @param token uuid generated by HTTP GET /analyze/token
     *
     * @return a list
     */
    @RequestMapping(value="/pattern/re-analyze/{token}", method = RequestMethod.GET)
    public ReAnalyzeResult reanalyzePatternRecognition(@PathVariable(value="token") String token,
                                                                    @RequestParam("numClusters") int numClusters,
                                                                    @RequestParam("minMembersPerCluster") int minMembersPerCluster) {
        // validation
        PatternRecogTask task = dao.getPatternRecogTask(token);
        File targetDir = new File(LOCAL_FILE_DOWNLOAD_PATH + sep + token);
        File targetFile = new File(LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + task.getFilename());
        if (!targetDir.exists() || !targetFile.exists() || task==null || numClusters < 1) {
            throw new BadRequestException("The task no longer exists, please re-upload your data and try again.");
        }

        // target file, directory, and db record exist, proceed with reanalyzing the file
        TaskInfo taskInfo = new TaskInfo(token, task.getFilename(), task.getFileSize());
        TaskScheduler scheduler = TaskScheduler.getInstance();
        int portToUse = scheduler.scheduleTask(taskInfo);

        double[][] regressionLines = null;
        try {
            // analyze the file again
            manager =  RManager.getInstance(portToUse);

            File rScript = new File(TEMPORAL_PATTERNS_R_SCRIPT_LOC);
            String absScriptPath = rScript.getAbsolutePath().replace("\\","\\\\");        // affects window env only

            manager.runRScript(absScriptPath);          // source the R script
            REXP rexp = manager.runRCommand("analyze.temporal.patterns('" + LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep +
                    task.getFilename() + "', '" + LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + "clustered_result.csv', "
                    + numClusters + ", " + minMembersPerCluster + ")");

            regressionLines = rexp.asDoubleMatrix();

            // notify scheduler that all R commands complete
            scheduler.endTask(portToUse);
            manager.closeConnection();
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("There was an issue with our R Engine. Please try again later.");
        }

        // update chunks of results in Database
        List<List<PatternRecogStat>> results = new FileAccess().getPatternRecogResults(token);
        int numChunks = dao.saveTaskResults(task, results);

        if (numChunks < 0) {
            throw new ServerException("There was an issue with uploading your file, please try again at a later time.");
        }

        // update task in Database
        task.setTimestamp(new Date());
        task.setNumClusters(numClusters);
        task.setMinMembersPerCluster(minMembersPerCluster);
        task.setNumChunks(numChunks);
        task.setRegressionLines(regressionLines);
        dao.saveOrUpdateTask(task); // always succeeds

        // specific to re-anlyze since front-end expects some return value
        // return the results as well as the new regression lines
        ReAnalyzeResult result = new ReAnalyzeResult();
        result.results = results;
        result.regressionLines = regressionLines;

        return result;
    }

    // local class for returning the results of a re-analyzed pattern recognition task
    class ReAnalyzeResult {
        public List<List<PatternRecogStat>> results;
        public double[][] regressionLines;
    }

    /**
     * Checks the integrity of a file located at the specified S3 path (objectKey).
     * If the file already exists, returns the results of checking the integrity of the
     * local file.
     *
     * @param token
     * @param objectKey
     * @return a human-readable message to be displayed to the user
     */
    @RequestMapping(value = "/integrity-check", method = RequestMethod.POST)
    public String checkIntegrityOfFile(@RequestParam("token") String token,
                                       @RequestParam("objectKey") String objectKey) {

        // validation
        String[] keyArr = objectKey.split("/");
        if (!(objectKey.startsWith("user-input/" + token)) ||
                !(keyArr[keyArr.length-2].equals(token)) ||
                keyArr.length != 3) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        String fileName = keyArr[keyArr.length-1];

        // check for the existence of this file
        // if it does not exist, download from s3
        File f = new File(LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + fileName);
        if (!f.exists()) {
            S3Status s3Status = s3Client.pullAndStoreObject(objectKey, LOCAL_FILE_DOWNLOAD_PATH + sep + token);
            int status = s3Status.getStatusCode();

            // error
            if (status == -1) {
                throw new ServerException("There was an error with your request, please try again later.");
            } else if (status > 0) {
                throw new BadRequestException(s3Client.getAWSStatusMessage(status));
            }
        }

        // TODO abineet, this is where you will call the integrity checker on the file
        // which is now located at: LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + fileName

        String pathToFile = LOCAL_FILE_DOWNLOAD_PATH + sep + token + sep + fileName;
        IntegrityChecker i = new IntegrityChecker();
        FeedBackType feedBackType =  i.checkIntegrity(pathToFile);

        if (feedBackType.getResult()) {
            String outString = "File check passed!\n";
            outString += "Total number of inputs = " + feedBackType.getTotalInputs() + ",\n";
            int percent = (feedBackType.getMissingInputs()*100/feedBackType.getTotalInputs());
            outString += "Total number of missing values = " + feedBackType.getMissingInputs() + "("+ percent + "%)..\n";
            outString += "Head over to the <a href='/upload-pass'>data pre-processing page</a> to continue.";
            return outString;
        } else {
            throw new BadRequestException("There was an issue with your input file: " + feedBackType.getErrorMessage() +
                    " - Please resolve this issue(s) and try again.");
        }
    }

    @RequestMapping(value = "/token", method = RequestMethod.GET)
    public String getToken() {
        return UUID.randomUUID().toString();
    }

    @RequestMapping(value= "/pre-process", method = RequestMethod.POST)
    public String updateStatsTable(@RequestParam("sourceFile") String sourceFile,
                                   @RequestParam("destFile") String destFile,
                                   @RequestParam("removeThresholdCheckbox") String removeThresholdCheckbox,
                                   @RequestParam("threshPercent") String thresholdPercent,
                                   @RequestParam("estPreference") String estimationPreference,
                                   @RequestParam("Norm") String normalizationType,
                                   @RequestParam("Trans") String transformationType,
                                   @RequestParam("Scaling") String scalingType){

        JSONObject JSONtoStore = new JSONObject();

        JSONtoStore.put("Source File", sourceFile);
        JSONtoStore.put("Destination File", destFile);
        JSONtoStore.put("Remove Threshold Checkbox", removeThresholdCheckbox);
        JSONtoStore.put("Threshold Percentage", thresholdPercent);
        JSONtoStore.put("Estimation Preference", estimationPreference);
        JSONtoStore.put("Normalization Type", normalizationType);
        JSONtoStore.put("Transformation Type", transformationType);
        JSONtoStore.put("Scaling Type", scalingType);

        fileInfoMap.put(destFile, JSONtoStore.toJSONString());

        return "";
    }

    @RequestMapping(value= "/siteTrafficChart", method = RequestMethod.POST)
    public String getSiteTrafficData(@RequestParam("ipAddress") String IP,
                                     @RequestParam("country") String countryName){
        trafficData.updateTrafficData(IP, countryName);
        return trafficData.getFormattedTrafficData();
    }

    @RequestMapping(value= "/getProcessingStats", method = RequestMethod.POST)
    public String getSiteTrafficData(@RequestParam("fileName") String fileName){
        if(fileInfoMap.containsKey(fileName))
            return fileInfoMap.get(fileName);

        JSONObject notFoundJSON = new JSONObject();
        notFoundJSON.put("Error", "Selected item is an unprocessed source file.");
        return  notFoundJSON.toJSONString();
    }

    @RequestMapping(value= "/updateSessionData", method = RequestMethod.POST)
    public String updateSessionData(@RequestParam("token") String token,
                                    @RequestParam("data") String data){

        dao.saveOrUpdateSessionData(new SessionData(token, data, System.currentTimeMillis()));
        return "success";
    }

    @RequestMapping(value= "/getSessionData", method = RequestMethod.POST)
    public String getSessionData(@RequestParam("token") String token){
        SessionData sessionData = dao.getSessionData(token);
        if (sessionData != null)
            return sessionData.getData();
        else
            return new JSONObject().put("Error", "Token does not exist").toString();

    }

    @RequestMapping(value= "/checkToken", method = RequestMethod.POST)
    public boolean checkToken(@RequestParam("token") String token){
        if (dao.getSessionData(token) == null)
            return false;
        return true;
    }




}
