package org.bd2k.metaprot.controller.rest;

import org.apache.log4j.Logger;
import org.bd2k.metaprot.aws.S3Client;
import org.bd2k.metaprot.aws.S3Status;
import org.bd2k.metaprot.data.FeedBackType;
import org.bd2k.metaprot.data.IntegrityChecker;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

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


    // "src/main/resources/R/scripts/r_sample_code.R"
    private final String CLEAN_DATASET_R_SCRIPT_LOC = rScriptLoc + "clean_dataset.R";
    private final String METABOLITES_R_SCRIPT_LOC = rScriptLoc + "r_sample_code.R";
    private final String PATTERN_R_SCRIPT_LOC = rScriptLoc + "time_series_pattern.R";
    private final String RESULT_VALIDATION_R_SCRIPT_LOC = rScriptLoc + "result_validation.R";
    private final String FIND_K_R_SCRIPT_LOC = rScriptLoc + "find_K.R";

    // handler to perform R related logic
    private RManager manager = null;

    //File Information Data Store
    private HashMap<String, String> fileInfoMap = new HashMap<>();


    /**
     * Helper function which executes an R script and returns the resulting REXP
     *
     * @param token uuid generated by HTTP GET /analyze/token
     * @param filename
     * @param fileSize
     * @param rFile location of R script
     * @param rCommand R command to execute
     * @return REXP from R command executed
     */

    public REXP executeRScript(String token, String filename, long fileSize,
                               String rFile, String rCommand) {

        try {
            TaskInfo taskInfo = new TaskInfo(token, filename, fileSize);
            TaskScheduler scheduler = TaskScheduler.getInstance();
            int portToUse = scheduler.scheduleTask(taskInfo);

            log.info("Port to use for Rserve: " + portToUse);

            // get manager instance and run R commands
            manager = RManager.getInstance(portToUse);
            //File rScript = new File(rFile);
            File rScript = new ClassPathResource(rFile).getFile();
            String absScriptPath = rScript.getAbsolutePath().replace("\\","\\\\");        // affects window env only

            manager.runRScript(absScriptPath);          // source the R script
            REXP rexp = manager.runRCommand(rCommand);

            scheduler.endTask(portToUse);   // notify scheduler that task is complete (all R commands done)
            manager.closeConnection();
            return rexp;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

    }


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
                                     @RequestParam("taskToken") String taskToken,
                                     @RequestParam("pThreshold") double pThreshold,
                                     @RequestParam("fcThreshold") double fcThreshold) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                pThreshold < 0 ||
                fcThreshold < 0 ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, root + taskToken);
        int status = s3Status.getStatusCode();

        log.info("new status s3: " + s3Status.toString());

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        // everything is OK on the server end, attempt to analyze the file
        try {
            String rCommand = "analyze.file('" + root + taskToken
                    + sep + keyArr[keyArr.length-1] + "', '" + root +
                    taskToken + sep + "data.csv', '" + root + taskToken + sep + "volcano.png', " +
                    pThreshold + ", " + fcThreshold + ")";

            executeRScript(taskToken, keyArr[keyArr.length-1], s3Status.getFileSize(),
                    METABOLITES_R_SCRIPT_LOC, rCommand);
        } catch (Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        // store results to database, TODO any new logic to read in all result files, for now just one, maybe just need to modify the file access function to return a list of lists
        List<List<MetaboliteStat>> totalResults = new ArrayList<>();
        List<MetaboliteStat> results = new FileAccess().getMetaboliteAnalysisResults(taskToken);
        totalResults.add(results);

        MetaboliteTask currentMetaboliteTask = new MetaboliteTask(taskToken, token, new Date(), keyArr[keyArr.length-1],
                s3Status.getFileSize(), pThreshold, fcThreshold, 0);

        // save the chunks
        int numChunks = dao.saveTaskResults(currentMetaboliteTask, totalResults);

        if (numChunks < 0) {
            throw new ServerException("There was an issue with uploading your file, please try again at a later time.");
        }

        // save the task
        currentMetaboliteTask.setNumChunks(numChunks);
        boolean taskSaved = dao.saveTask(currentMetaboliteTask);

        if (!taskSaved) {
            throw new BadRequestException("There was an issue with your task token. Please try again at a later time.");
        }

        // analysis complete and results recorded, safe to delete all temporary files
        new FileAccess().deleteTemporaryAnalysisFiles(taskToken);

        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a target='_blank' href='/metabolite-analysis/results/" + taskToken + "'>results</a>");
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
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        String fileName = keyArr[keyArr.length-1];

        // check for the existence of this file
        // if it does not exist, download from s3
        File f = new File(root + token + sep + fileName);
        if (!f.exists()) {
            S3Status s3Status = s3Client.pullAndStoreObject(objectKey, root + token);
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

        String pathToFile = root + token + sep + fileName;
        IntegrityChecker i = new IntegrityChecker();
        FeedBackType feedBackType =  i.checkIntegrity(pathToFile);

        new FileAccess().deleteTemporaryAnalysisFiles(token);

        if (feedBackType.getResult()) {
            String outString = "File check passed!\n";
            outString += "Total number of inputs = " + feedBackType.getTotalInputs() + ",\n";
            int percent = (feedBackType.getMissingInputs()*100/feedBackType.getTotalInputs());
            outString += "Total number of missing values = " + feedBackType.getMissingInputs() + "("+ percent + "%)..\n";
            outString += "Head over to the <Link to='/upload-pass'>data pre-processing page</Link> to continue.";
            return outString;
        } else {
            throw new BadRequestException("There was an issue with your input file: " + feedBackType.getErrorMessage() +
                    " - Please resolve this issue(s) and try again.");
        }
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
    @RequestMapping(value = "/clean-dataset", method = RequestMethod.POST)
    public String cleanDataset(@RequestParam("token") String token,
                               @RequestParam("objectKey") String objectKey) {

        // validation
        String[] keyArr = objectKey.split("/");
        if (!(objectKey.startsWith("user-input/" + token)) ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }



        // grab uploaded file from S3
        S3Status s3Status = s3Client.pullAndStoreObject(objectKey, root + token);
        int status = s3Status.getStatusCode();

        log.info("new status s3: " + s3Status.toString());

        // check for errors in s3 pull/store
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }


        String fileName = keyArr[keyArr.length-1];
        String inputPath = root + token + sep + fileName;
        String outputName = fileName.replace(".csv", "-CLEAN.csv");
        String outputPath = root + token + sep + outputName;

        // everything is OK on the server end, attempt to analyze the file
        try {

            String rCommand = "clean.dataset('" + inputPath + "', '" + outputPath + "')";
            REXP rexp = executeRScript(token, fileName, s3Status.getFileSize(),
                    CLEAN_DATASET_R_SCRIPT_LOC, rCommand);
        } catch(Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        s3Client.uploadToS3(objectKey.replace(".csv", "-CLEAN.csv"), new File(outputPath));

        new FileAccess().deleteTemporaryAnalysisFiles(token);

        JSONObject obj = new JSONObject();
        obj.put("filename", outputName);
        obj.put("message", "The dataset has been successfully cleaned and saved as: " + outputName);
        return obj.toJSONString();

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

    @RequestMapping(value= "/getProcessingStats", method = RequestMethod.POST)
    public String getProcessingStats(@RequestParam("fileName") String fileName){
        if(fileInfoMap.containsKey(fileName))
            return fileInfoMap.get(fileName);

        JSONObject notFoundJSON = new JSONObject();
        notFoundJSON.put("Error", "Selected item is an unprocessed source file.");
        return  notFoundJSON.toJSONString();
    }



    /**
     * Analyzes an uploaded CSV file for time series analysis.
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
    public String analyzePattern(@PathVariable("token") String token,
                                    @RequestParam("objectKey") String key,
                                    @RequestParam("taskToken") String taskToken) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, root + taskToken);
        int status = s3Status.getStatusCode();

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        // everything is OK on the server end, attempt to analyze the file
        try {
            String rCommand = "analyze.patterns('" + root + taskToken
                    + sep + keyArr[keyArr.length-1] + "', '" +
                    root + taskToken + sep + "pattern_concentrations.csv', '" +
                    root + taskToken + sep + "pattern_significance.csv')";

            executeRScript(taskToken, keyArr[keyArr.length-1], s3Status.getFileSize(),
                    PATTERN_R_SCRIPT_LOC, rCommand);
        } catch (Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        // store results to database
        List<PatternRecognitionValue> values = new FileAccess().getPatternRecognitionConcentrations(taskToken);
        List<PatternRecognitionSignificance> significances = new FileAccess().getPatternRecognitionSignificance(taskToken);
        PatternRecognitionResults results = new PatternRecognitionResults(values, significances);

        Task currentTask = new Task(taskToken, token, new Date(), keyArr[keyArr.length-1],
                s3Status.getFileSize(), 0, Task.PATTERN);

        // save the chunks
        int numChunks = dao.saveTaskResults(currentTask, results);

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
        new FileAccess().deleteTemporaryAnalysisFiles(taskToken);


        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a target='_blank' href='/pattern/results/" + taskToken + "'>results</a>");

    }

    /**
     * Analyzes an uploaded CSV file for Result Validation.
     *
     * Maps to:
     *
     * HTTP POST /analyze/result-validation/{token}
     *
     * @param token uuid generated by HTTP GET /analyze/token
     * @param key AWS S3 key pointing to the uploaded file
     *
     * @return an HTML formatted message ready to be displayed to the end user.
     */
    @RequestMapping(value = "/result-validation/{token}", method = RequestMethod.POST)
    public String resultValidation(@PathVariable("token") String token,
                                 @RequestParam("objectKey") String key,
                                 @RequestParam("taskToken") String taskToken) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, root + taskToken);
        int status = s3Status.getStatusCode();

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        String filename = keyArr[keyArr.length-1];
        String inputFile = root + taskToken + sep + filename;
        String outputPlot = root + taskToken + sep + "static3Dplot.png";
        String outputData = root + taskToken + sep + "dynamic3Ddata.csv";

        // everything is OK on the server end, attempt to analyze the file
        try {
            String rCommand = String.format("analyze.result.validation('%s', '%s', '%s')",
                    inputFile, outputPlot, outputData);

            executeRScript(taskToken, keyArr[keyArr.length-1], s3Status.getFileSize(),
                    RESULT_VALIDATION_R_SCRIPT_LOC, rCommand);
        } catch (Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        Task currentTask = null;
        int numChunks = 0;
        // store results to database
        try {
            ResultValidationResults results = new FileAccess().getResultValidationResults(taskToken);
            currentTask = new Task(taskToken, token, new Date(), filename,
                    s3Status.getFileSize(), 0, Task.RESULT_VALIDATION);

            // save the chunks
            numChunks = dao.saveTaskResults(currentTask, results);

            if (numChunks < 0) {
                throw new IOException("Unable to save task results in database");
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new ServerException("There was an issue with uploading your file, please try again at a later time.");

        }

        // save the task
        currentTask.setNumChunks(numChunks);
        boolean taskSaved = dao.saveTask(currentTask);

        if (!taskSaved) {
            throw new BadRequestException("There was an issue with your task token. Please try again at a later time.");
        }

        // analysis complete and results recorded, safe to delete all temporary files
        new FileAccess().deleteTemporaryAnalysisFiles(taskToken);

        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a target='_blank' href='/result-validation/results/" + taskToken + "'>results</a>");

    }

    /**
     * Analyzes an uploaded CSV file for Result Validation.
     *
     * Maps to:
     *
     * HTTP POST /analyze/result-validation/{token}
     *
     * @param token uuid generated by HTTP GET /analyze/token
     * @param key AWS S3 key pointing to the uploaded file
     *
     * @return an HTML formatted message ready to be displayed to the end user.
     */
    @RequestMapping(value = "/integration-tool/{token}", method = RequestMethod.POST)
    public String integrationTool(@PathVariable("token") String token,
                                   @RequestParam("objectKey") String key,
                                   @RequestParam("taskToken") String taskToken) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, root + taskToken);
        int status = s3Status.getStatusCode();

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        String filename = keyArr[keyArr.length-1];

        Task currentTask = null;
        int numChunks = 0;
        // store results to database
        try {
            IntegrationToolResults results = new FileAccess().getIntegrationToolResults(taskToken, keyArr[keyArr.length-1]);
            currentTask = new Task(taskToken, token, new Date(), filename,
                    s3Status.getFileSize(), 0, Task.INTEGRATION_TOOL);

            // save the chunks
            numChunks = dao.saveTaskResults(currentTask, results);

            if (numChunks < 0) {
                throw new IOException("Unable to save task results in database");
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new ServerException("There was an issue with uploading your file, please try again at a later time.");

        }

        // save the task
        currentTask.setNumChunks(numChunks);
        /*
        boolean taskSaved = dao.saveTask(currentTask);

        if (!taskSaved) {
            throw new BadRequestException("There was an issue with your task token. Please try again at a later time.");
        }
        */
        dao.saveOrUpdateTask(currentTask);

        // analysis complete and results recorded, safe to delete all temporary files
        new FileAccess().deleteTemporaryAnalysisFiles(taskToken);

        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed!<br/>%s<br/>%s";

        return String.format(successMessage,
                "<a target='_blank' href='/integration-tool/results/" + taskToken + "'>View table</a>",
                "<a target='_blank' href='/integration-tool/visual/" + taskToken + "'>View visualization</a>");

    }


    /**
     * Analyzes an uploaded CSV file for time series analysis.
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
    @RequestMapping(value = "/dtw-elbow/{token}", method = RequestMethod.POST)
    public String analyzeDTWElbow(@PathVariable("token") String token,
                                 @RequestParam("objectKey") String key,
                                 @RequestParam("taskToken") String taskToken,
                                 @RequestParam("minCluster") int minCluster,
                                 @RequestParam("maxCluster") int maxCluster) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, root + taskToken);
        int status = s3Status.getStatusCode();

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        // everything is OK on the server end, attempt to analyze the file
        try {
            String rCommand = "elbow_plot('" + root + taskToken
                    + sep + keyArr[keyArr.length-1] + "', " +
                    minCluster + ", " + maxCluster + ", '" +
                    root + taskToken + sep + "elbow_plot.jpg')";

            executeRScript(taskToken, keyArr[keyArr.length-1], s3Status.getFileSize(),
                    FIND_K_R_SCRIPT_LOC, rCommand);
        } catch (Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }

        // store results to database

        String results = new FileAccess().getElbowPlotResults(taskToken);

        Task currentTask = new Task(taskToken, token, new Date(), keyArr[keyArr.length-1],
                s3Status.getFileSize(), 0, Task.DTW_ELBOW);

        // save the chunks
        int numChunks = dao.saveTaskResults(currentTask, results);

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
        new FileAccess().deleteTemporaryAnalysisFiles(taskToken);


        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a target='_blank' href='/dtw-cluster/elbow-plot-results/" + taskToken + "'>results</a>");

    }


    /**
     * Analyzes an uploaded CSV file for time series analysis.
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
    @RequestMapping(value = "/dtw-cluster/{token}", method = RequestMethod.POST)
    public String analyzeDTWCluster(@PathVariable("token") String token,
                                  @RequestParam("objectKey") String key,
                                  @RequestParam("taskToken") String taskToken,
                                  @RequestParam("numCluster") int numCluster) {

        // validation
        String[] keyArr = key.split("/");
        if (!(key.startsWith("user-input/" + token)) ||
                keyArr.length != 3 ||
                !(keyArr[keyArr.length-2].equals(token))) {

            // should return error message
            throw new BadRequestException("Invalid request, please try again later.");
        }

        S3Status s3Status = s3Client.pullAndStoreObject(key, root + taskToken);
        int status = s3Status.getStatusCode();

        // error
        if (status == -1) {
            throw new ServerException("There was an error with your request, please try again later.");
        } else if (status > 0) {
            throw new BadRequestException(s3Client.getAWSStatusMessage(status));
        }

        // everything is OK on the server end, attempt to analyze the file
        try {
            String rCommand = "partitional_cluster('" + root + taskToken
                    + sep + keyArr[keyArr.length-1] + "', " +
                    numCluster + ", '" +
                    root + taskToken + sep + "dtw_plot.jpg','" + root + taskToken + sep + "groups.txt')";
            System.out.println(rCommand);
            executeRScript(taskToken, keyArr[keyArr.length-1], s3Status.getFileSize(),
                    FIND_K_R_SCRIPT_LOC, rCommand);
        } catch (Exception e) {
            // handle exception so that we can return appropriate error messages
            e.printStackTrace();
            throw new ServerException("There was an error with our R Engine. Please try again at a later time.");
        }


        // store results to database

        DTWClusterResults results = new FileAccess().getDTWClusterResults(taskToken);

        Task currentTask = new Task(taskToken, token, new Date(), keyArr[keyArr.length-1],
                s3Status.getFileSize(), 0, Task.DTW_CLUSTER);

        // save the chunks
        int numChunks = dao.saveTaskResults(currentTask, results);

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
        new FileAccess().deleteTemporaryAnalysisFiles(taskToken);


        // everything went well, success message
        String successMessage = "Your file has been successfully analyzed! Head over to the %s page" +
                " to see the report.";

        return String.format(successMessage, "<a target='_blank' href='/dtw-cluster/cluster-results/" + taskToken + "'>results</a>");

    }
}
