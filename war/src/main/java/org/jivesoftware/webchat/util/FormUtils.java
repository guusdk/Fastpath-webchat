/**
 * $RCSfile$
 * $Revision: 24191 $
 * $Date: 2005-11-28 20:16:08 -0800 (Mon, 28 Nov 2005) $
 *
 * Copyright (C) 1999-2005 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution, or a commercial license
 * agreement with Jive.
 */
package org.jivesoftware.webchat.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.FormField.Option;

public class FormUtils {

    private FormUtils() {
    }

    public static String createAnswers(FormField formField, HttpServletRequest request) {
        final StringBuffer builder = new StringBuffer();
        if (formField.getType().equals(FormField.Type.text_single)) {
            String cookieValue = getCookieValueForField(formField.getVariable(), request);
            String insertValue = "";
            if(ModelUtil.hasLength(cookieValue)){
                insertValue = "value=\""+StringUtils.escapeHTMLTags(cookieValue)+"\"";
            }
            builder.append("<input type=\"text\" name=\"" + formField.getVariable() + "\" "+insertValue+" style=\"width:75%\">");
        }
        else if (formField.getType().equals(FormField.Type.text_multi)) {
            builder.append("<textarea name=\"" + formField.getVariable() + "\" cols=\"30\" rows=\"3\">");
            builder.append("</textarea>");
        }
        else if (formField.getType().equals(FormField.Type.list_single)) {
            builder.append("<select name=\"" + formField.getVariable() + "\" >");
            List<Option> options = formField.getOptions();
            String cookieValue = ModelUtil.emptyStringIfNull(getCookieValueForField(formField.getVariable(), request));
            for (Option option : options){
                String selected = option.getValue().equals(cookieValue) ? "selected" : "";
                builder.append("<option value=\"" + StringUtils.escapeHTMLTags(option.getValue()) + "\" "+selected+">" + option.getLabel() + "</option>");
            }
            builder.append("</select>");
        }
        else if (formField.getType().equals(FormField.Type.bool)) {
          List<Option> options = formField.getOptions();
            int counter = 0;
            for (Option option : options){
                String value = option.getLabel();
                builder.append("<input type=\"checkbox\" value=\"" + value + "\" name=\"" + formField.getVariable() + counter + "\">");
                builder.append("&nbsp;");
                builder.append(StringUtils.escapeHTMLTags(value));
                builder.append("<br/>");
                counter++;
            }
        }
        else if (formField.getType().equals(FormField.Type.list_multi)) {
          List<Option> options = formField.getOptions();
          for (Option option : options){
                String value = option.getLabel();
                builder.append("<input type=\"radio\" value=\"" + value + "\" name=\"" + formField.getVariable() + "\">");
                builder.append("&nbsp;");
                builder.append(StringUtils.escapeHTMLTags(value));
                builder.append("<br/>");
            }
        }
        else if (formField.getType().equals(FormField.Type.hidden)) {
            String name = formField.getVariable();
            List<String> values = formField.getValues();
            String value = "";
            for (String item : values) {
                value = " value=\"" + StringUtils.escapeHTMLTags(item) + "\"";
            }
            builder.append("<input type=\"hidden\" name=\"" + name + "\" " + value + " />");
        }
        else if (formField.getType().equals(FormField.Type.text_private)) {
            String cookieValue = getCookieValueForField(formField.getVariable(), request);
            String insertValue = "";
            if(ModelUtil.hasLength(cookieValue)){
                insertValue = "value=\""+StringUtils.escapeHTMLTags(cookieValue)+"\"";
            }
            builder.append("<input type=\"password\" name=\"" + formField.getVariable() + "\" "+insertValue+" style=\"width:75%\">");
        }

        return builder.toString();
    }

    /**
     * Handles String building of dynamic variables such as getRequest, getSession, getCookie
     * values.
     *
     * @param formField the hidden form field.
     * @param request   the http request object.
     * @return the built string, if form field is hidden, else returns an empty string.
     */
    public static String createDynamicField(FormField formField, HttpServletRequest request) {
        if (formField.getType().equals(FormField.Type.hidden)) {
            String name = formField.getVariable();
            List<String> values = formField.getValues();
            String value = "";
            for (String item : values){
                value = item;
            }
            if (value.startsWith("getRequest_")) {
                String variableToGet = value.substring(11);
                value = request.getParameter(variableToGet);
                if (!ModelUtil.hasLength(value)) {
                    return "";
                }
            }
            else if (value.startsWith("getCookie_")) {
                String variableToGet = value.substring(10);
                Cookie[] cookies = request.getCookies();
                final int no = cookies != null ? cookies.length : 0;
                String foundValue = null;
                for (int i = 0; i < no; i++) {
                    Cookie foundCookie = cookies[i];
                    String cookieName = foundCookie.getName();
                    if (variableToGet.equals(cookieName)) {
                        foundValue = foundCookie.getValue();
                        value = foundValue;
                    }
                }
                if (!ModelUtil.hasLength(foundValue)) {
                    return "";
                }
            }
            else if (value.startsWith("getHeader_")) {
                String variableToGet = value.substring(10);
                String headerValue = request.getHeader(variableToGet);
                value = headerValue;
                if (!ModelUtil.hasLength(value)) {
                    return "";
                }
            }
            else if (value.startsWith("getSession_")) {
                String variableToGet = value.substring(11);
                HttpSession session = request.getSession();
                Object objectValue = session.getAttribute(variableToGet);
                value = objectValue.toString();
                if (!ModelUtil.hasLength(value)) {
                    return "";
                }
            }
            String iV = ModelUtil.hasLength(value) ? "value=\"" + StringUtils.escapeHTMLTags(value) + "\"" : "";
            return "<input type=\"hidden\" name=\"" + name + "\" " + iV + " />";
        }
        return "";
    }

    public static String getCookieValueForField(String fieldName, HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        String value = null;

        if (cookies != null) {
            for (int i = 0; i < cookies.length; i++) {
                String name = cookies[i].getName();
                if(name.equals("la_"+fieldName)){
                    try {
                        value = URLDecoder.decode(cookies[i].getValue(), "UTF-8");
                    }
                    catch (UnsupportedEncodingException e) {
                         WebLog.logError("Error retrieving cookie value.", e);
                    }
                    break;
                }
            }
        }
        return value;
    }
}
