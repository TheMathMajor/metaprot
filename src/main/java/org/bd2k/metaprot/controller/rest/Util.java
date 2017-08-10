package org.bd2k.metaprot.controller.rest;

import org.bd2k.metaprot.data.GoogleAnalytics;
import org.bd2k.metaprot.data.GoogleAnalyticsReport;
import org.bd2k.metaprot.dbaccess.DAOImpl;
import org.bd2k.metaprot.exception.ServerException;
import org.bd2k.metaprot.model.SessionData;
import org.bd2k.metaprot.util.EmailService;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * REST controller for misc. endpoints for utility functions
 *
 * Created by Nate Sookwongse on 8/3/17.
 */
@RestController
@RequestMapping("/util")
public class Util {

    @Autowired
    private DAOImpl dao;

    @Autowired
    private EmailService emailService;

    @RequestMapping(value = "/token", method = RequestMethod.GET)
    public String getToken() {
        return UUID.randomUUID().toString();
    }

    @RequestMapping(value= "/checkToken", method = RequestMethod.POST)
    public boolean checkToken(@RequestParam("token") String token){
        if (dao.getSessionData(token) == null)
            return false;
        return true;
    }

    @RequestMapping(value= "/getSessionData", method = RequestMethod.POST)
    public String getSessionData(@RequestParam("token") String token){
        SessionData sessionData = dao.getSessionData(token);
        if (sessionData != null)
            return sessionData.getData();
        else
            return new JSONObject().put("Error", "Token does not exist").toString();

    }

    @RequestMapping(value= "/googleAnalyticsReport", method = RequestMethod.GET)
    public GoogleAnalyticsReport getGoogleAnalyticsReport(){
        return GoogleAnalytics.getReport();
    }

    @RequestMapping(value = "/email", method = RequestMethod.GET)
    public String sendEmail() {
        emailService.sendSimpleMessage("nnsookwon@gmail.com", "test", "lol");
        return "done";
    }

    @RequestMapping(value = "/sendFeedback", method = RequestMethod.POST)
    public String sendFeedback(@RequestParam("email") String fromEmail,
                               @RequestParam("subject") String subject,
                               @RequestParam("text") String text) {
        String content = "From: " + fromEmail + "\n\n" + "Feedback: " + text;
        try {
            emailService.sendFeedback(subject, content);
        } catch (MailException e) {
            e.printStackTrace();
            throw new ServerException("An error has occurred. Please try again later.");
        }
        return "Thank you for your feedback!";
    }

    @RequestMapping(value = "/shareToken", method = RequestMethod.POST)
    public String shareToken(@RequestParam("email") String toEmail,
                             @RequestParam("token") String token,
                             HttpServletRequest request) {
        String url = request.getRequestURL().toString().replace("util/shareToken", "upload/"+token);
        String content = "Please click here: " + url;

        try {
            emailService.sendSimpleMessage(toEmail, "MetProt Shared Session Token", content);
        } catch (MailException e) {
            e.printStackTrace();
            throw new ServerException("An error has occurred. Please try again later.");
        }
        return "Your session token has been successfully sent!";

    }

}
