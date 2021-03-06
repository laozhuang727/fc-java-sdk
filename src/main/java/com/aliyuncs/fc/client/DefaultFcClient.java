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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package com.aliyuncs.fc.client;

import com.aliyuncs.fc.constants.HeaderKeys;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import com.aliyuncs.fc.auth.FcSignatureComposer;
import com.aliyuncs.fc.config.Config;
import com.aliyuncs.fc.exceptions.ClientException;
import com.aliyuncs.fc.exceptions.ServerException;
import com.aliyuncs.fc.http.HttpRequest;
import com.aliyuncs.fc.http.HttpResponse;
import com.google.gson.Gson;
import com.aliyuncs.fc.auth.AcsURLEncoder;
import com.aliyuncs.fc.model.PrepareUrl;
import com.aliyuncs.fc.utils.ParameterHelper;

public class DefaultFcClient {
    public final static Boolean AUTO_RETRY = true;
    public final static int MAX_RETRIES = 3;
    private final Config config;

    public DefaultFcClient(Config config) {
        this.config = config;
    }

    public String composeUrl(String endpoint, Map<String, String> queries)
        throws UnsupportedEncodingException {

        Map<String, String> mapQueries = queries;
        StringBuilder urlBuilder = new StringBuilder("");
        urlBuilder.append(endpoint);
        if (-1 == urlBuilder.indexOf("?")) {
            urlBuilder.append("?");
        } else if (!urlBuilder.toString().endsWith("?")) {
            urlBuilder.append("&");
        }
        String url = urlBuilder.toString();
        if (queries != null && queries.size() > 0) {
            String query = concatQueryString(mapQueries);
            url = urlBuilder.append(query).toString();
        }
        if (url.endsWith("?") || url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * concate query string parameters (e.g. name=foo)
     * @param parameters query parameters
     * @return concatenated query string
     * @throws UnsupportedEncodingException exceptions
     */
    public String concatQueryString(Map<String, String> parameters)
        throws UnsupportedEncodingException {
        if (null == parameters) {
            return null;
        }

        StringBuilder urlBuilder = new StringBuilder("");
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            urlBuilder.append(AcsURLEncoder.encode(key));
            if (val != null) {
                urlBuilder.append("=").append(AcsURLEncoder.encode(val));
            }
            urlBuilder.append("&");
        }

        int strIndex = urlBuilder.length();
        if (parameters.size() > 0) {
            urlBuilder.deleteCharAt(strIndex - 1);
        }

        return urlBuilder.toString();
    }

    public Map<String, String> getHeader(Map<String, String> header, byte[] payload, String form) {
        if (header == null) {
            header = new HashMap<String, String>();
        }
        header.put("User-Agent", config.getUserAgent());
        header.put("Accept", "application/json");
        header.put("Content-Type", form);
        header.put("x-fc-account-id", config.getUid());
        if (payload != null) {
            header.put("Content-MD5", ParameterHelper.md5Sum(payload));
        }
        if (!Strings.isNullOrEmpty(config.getSecurityToken())) {
            header.put("x-fc-security-token", config.getSecurityToken());
        }
        return header;
    }

    public PrepareUrl signRequest(HttpRequest request, String form, String method)
        throws InvalidKeyException, IllegalStateException, UnsupportedEncodingException, NoSuchAlgorithmException {

        Map<String, String> imutableMap = null;
        if (request.getHeaders() != null) {
            imutableMap = request.getHeaders();
        } else {
            imutableMap = new HashMap<String, String>();
        }
        String accessKeyId = config.getAccessKeyID();
        String accessSecret = config.getAccessKeySecret();

        Preconditions.checkArgument(!Strings.isNullOrEmpty(accessKeyId), "Access key cannot be blank");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(accessSecret), "Secret key cannot be blank");
        imutableMap = FcSignatureComposer.refreshSignParameters(imutableMap);

        // Get relevant path
        String uri = request.getPath();

        // Set all headers
        imutableMap = getHeader(imutableMap, request.getPayload(), form);

        // Sign URL
        String strToSign = FcSignatureComposer.composeStringToSign(method, uri, imutableMap);
        String signature = FcSignatureComposer.signString(strToSign, accessSecret);

        // Set signature
        imutableMap.put("Authorization", "FC " + accessKeyId + ":" + signature);
        String allPath = composeUrl(config.getEndpoint() + request.getPath(),
            request.getQueryParams());
        return new PrepareUrl(allPath);
    }

    public HttpResponse doAction(HttpRequest request, String form, String method)
        throws ClientException, ServerException {
        request.validate();
        try {
            PrepareUrl prepareUrl = signRequest(request, form, method);
            int retryTimes = 1;
            HttpResponse response = HttpResponse.getResponse(prepareUrl.getUrl(), request, method, config.getConnectTimeoutMillis(),
                config.getReadTimeoutMillis());

            while (500 <= response.getStatus() && AUTO_RETRY && retryTimes < MAX_RETRIES) {
                prepareUrl = signRequest(request, form, method);
                response = HttpResponse.getResponse(prepareUrl.getUrl(), request, method,
                    config.getConnectTimeoutMillis(), config.getReadTimeoutMillis());
                retryTimes++;
            }
            if (response.getStatus() >= 500) {
                String requestId = response.getHeaderValue(HeaderKeys.REQUEST_ID);
                String stringContent = response.getContent() == null ? "" : new String(response.getContent());
                ServerException se;
                try {
                    se = new Gson().fromJson(stringContent, ServerException.class);
                } catch (JsonParseException e) {
                    se = new ServerException("InternalServiceError", "Failed to parse response content", requestId);
                }
                se.setStatusCode(response.getStatus());
                se.setRequestId(requestId);
                throw se;
            } else if (response.getStatus() >= 300) {
                ClientException ce;
                if (response.getContent() == null) {
                    ce = new ClientException("SDK.ServerUnreachable", "Failed to get response content from server");
                } else {
                    try {
                        ce = new Gson().fromJson(new String(response.getContent()), ClientException.class);
                    } catch (JsonParseException e) {
                        ce = new ClientException("SDK.ResponseNotParsable", "Failed to parse response content", e);
                    }
                }
                if (ce == null) {
                    ce = new ClientException("SDK.UnknownError", "Unknown client error");
                }
                ce.setStatusCode(response.getStatus());
                ce.setRequestId(response.getHeaderValue(HeaderKeys.REQUEST_ID));
                throw ce;
            }
            return response;
        } catch (InvalidKeyException exp) {
            throw new ClientException("SDK.InvalidAccessSecret", "Speicified access secret is not valid.");
        } catch (SocketTimeoutException exp){
            throw new ClientException("SDK.ServerUnreachable", "SocketTimeoutException has occurred on a socket read or accept.");
        } catch (IOException exp) {
            throw new ClientException("SDK.ServerUnreachable", "Server unreachable: " + exp.toString());
        } catch (NoSuchAlgorithmException exp) {
            throw new ClientException("SDK.InvalidMD5Algorithm", "MD5 hash is not supported by client side.");
        }
    }
}
