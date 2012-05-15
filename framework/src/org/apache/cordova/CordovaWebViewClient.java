/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova;

import java.util.Hashtable;

import org.apache.cordova.api.CordovaInterface;
import org.apache.cordova.api.LOG;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * This class is the WebViewClient that implements callbacks for our web view.
 */
public class CordovaWebViewClient extends WebViewClient {

    private static final String TAG = "Cordova";
    CordovaInterface ctx;
    CordovaWebView appView;
    private boolean doClearHistory = false;

    /** The authorization tokens. */
    private Hashtable<String, AuthenticationToken> authenticationTokens = new Hashtable<String, AuthenticationToken>();

    /**
     * Constructor.
     * 
     * @param ctx
     */
    public CordovaWebViewClient(CordovaInterface ctx) {
        this.ctx = ctx;
    }

    /**
     * Constructor.
     * 
     * @param ctx
     * @param view
     */
    public CordovaWebViewClient(CordovaInterface ctx, CordovaWebView view) {
        this.ctx = ctx;
        this.appView = view;
    }

    /**
     * Constructor.
     * 
     * @param view
     */
    public void setWebView(CordovaWebView view) {
        this.appView = view;
    }

    /**
     * Give the host application a chance to take over the control when a new url 
     * is about to be loaded in the current WebView.
     * 
     * @param view          The WebView that is initiating the callback.
     * @param url           The url to be loaded.
     * @return              true to override, false for default behavior
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {

        // First give any plugins the chance to handle the url themselves
        if ((this.appView.pluginManager != null) && this.appView.pluginManager.onOverrideUrlLoading(url)) {
        }

        // If dialing phone (tel:5551212)
        else if (url.startsWith(WebView.SCHEME_TEL)) {
            try {
                Intent intent = new Intent(Intent.ACTION_DIAL);
                intent.setData(Uri.parse(url));
                this.ctx.getActivity().startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                LOG.e(TAG, "Error dialing " + url + ": " + e.toString());
            }
        }

        // If displaying map (geo:0,0?q=address)
        else if (url.startsWith("geo:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                this.ctx.getActivity().startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                LOG.e(TAG, "Error showing map " + url + ": " + e.toString());
            }
        }

        // If sending email (mailto:abc@corp.com)
        else if (url.startsWith(WebView.SCHEME_MAILTO)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                this.ctx.getActivity().startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                LOG.e(TAG, "Error sending email " + url + ": " + e.toString());
            }
        }

        // If sms:5551212?body=This is the message
        else if (url.startsWith("sms:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);

                // Get address
                String address = null;
                int parmIndex = url.indexOf('?');
                if (parmIndex == -1) {
                    address = url.substring(4);
                }
                else {
                    address = url.substring(4, parmIndex);

                    // If body, then set sms body
                    Uri uri = Uri.parse(url);
                    String query = uri.getQuery();
                    if (query != null) {
                        if (query.startsWith("body=")) {
                            intent.putExtra("sms_body", query.substring(5));
                        }
                    }
                }
                intent.setData(Uri.parse("sms:" + address));
                intent.putExtra("address", address);
                intent.setType("vnd.android-dir/mms-sms");
                this.ctx.getActivity().startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                LOG.e(TAG, "Error sending sms " + url + ":" + e.toString());
            }
        }

        // All else
        else {

            // If our app or file:, then load into a new Cordova webview container by starting a new instance of our activity.
            // Our app continues to run.  When BACK is pressed, our app is redisplayed.
            if (url.startsWith("file://") || url.indexOf(this.appView.baseUrl) == 0 || this.appView.isUrlWhiteListed(url)) {
                //This will fix iFrames
                if (appView.useBrowserHistory)
                    return false;
                else
                    this.appView.loadUrl(url);
            }

            // If not our application, let default viewer handle
            else {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    this.ctx.getActivity().startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    LOG.e(TAG, "Error loading url " + url, e);
                }
            }
        }
        return true;
    }

    /**
     * On received http auth request.
     * The method reacts on all registered authentication tokens. There is one and only one authentication token for any host + realm combination 
     * 
     * @param view
     * @param handler
     * @param host
     * @param realm
     */
    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {

        // Get the authentication token
        AuthenticationToken token = this.getAuthenticationToken(host, realm);
        if (token != null) {
            handler.proceed(token.getUserName(), token.getPassword());
        }
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        // Clear history so history.back() doesn't do anything.  
        // So we can reinit() native side CallbackServer & PluginManager.
        if (!this.appView.useBrowserHistory) {
            view.clearHistory();
            this.doClearHistory = true;
        }

        // Create callback server and plugin manager
        if (this.appView.callbackServer == null) {
            this.appView.callbackServer = new CallbackServer();
            this.appView.callbackServer.init(url);
        }
        else {
            this.appView.callbackServer.reinit(url);
        }

        // Broadcast message that page has loaded
        this.appView.postMessage("onPageStarted", url);
    }

    /**
     * Notify the host application that a page has finished loading.
     * 
     * @param view          The webview initiating the callback.
     * @param url           The url of the page.
     */
    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        LOG.d(TAG, "onPageFinished(" + url + ")");

        /**
         * Because of a timing issue we need to clear this history in onPageFinished as well as 
         * onPageStarted. However we only want to do this if the doClearHistory boolean is set to 
         * true. You see when you load a url with a # in it which is common in jQuery applications
         * onPageStared is not called. Clearing the history at that point would break jQuery apps.
         */
        if (this.doClearHistory) {
            view.clearHistory();
            this.doClearHistory = false;
        }

        // Clear timeout flag
        this.appView.loadUrlTimeout++;

        // Try firing the onNativeReady event in JS. If it fails because the JS is
        // not loaded yet then just set a flag so that the onNativeReady can be fired
        // from the JS side when the JS gets to that code.
        if (!url.equals("about:blank")) {
            this.appView.loadUrl("javascript:try{ cordova.require('cordova/channel').onNativeReady.fire();}catch(e){_nativeReady = true;}");
            this.appView.postMessage("onNativeReady", null);
        }

        // Broadcast message that page has loaded
        this.appView.postMessage("onPageFinished", url);

        // Make app visible after 2 sec in case there was a JS error and Cordova JS never initialized correctly
        if (this.appView.getVisibility() == View.INVISIBLE) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(2000);
                        ctx.getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                appView.postMessage("spinner", "stop");
                            }
                        });
                    } catch (InterruptedException e) {
                    }
                }
            });
            t.start();
        }

        // Shutdown if blank loaded
        if (url.equals("about:blank")) {
            if (this.appView.callbackServer != null) {
                this.appView.callbackServer.destroy();
            }
            appView.postMessage("exit", null);
        }
    }

    /**
     * Report an error to the host application. These errors are unrecoverable (i.e. the main resource is unavailable). 
     * The errorCode parameter corresponds to one of the ERROR_* constants.
     *
     * @param view          The WebView that is initiating the callback.
     * @param errorCode     The error code corresponding to an ERROR_* value.
     * @param description   A String describing the error.
     * @param failingUrl    The url that failed to load. 
     */
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        LOG.d(TAG, "CordovaWebViewClient.onReceivedError: Error code=%s Description=%s URL=%s", errorCode, description, failingUrl);

        // Clear timeout flag
        this.appView.loadUrlTimeout++;

        // Handle error
        JSONObject data = new JSONObject();
        try {
            data.put("errorCode", errorCode);
            data.put("description", description);
            data.put("url", failingUrl);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        this.appView.postMessage("onReceivedError", data);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {

        final String packageName = this.ctx.getActivity().getPackageName();
        final PackageManager pm = this.ctx.getActivity().getPackageManager();
        ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            if ((appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // debug = true
                handler.proceed();
                return;
            } else {
                // debug = false
                super.onReceivedSslError(view, handler, error);
            }
        } catch (NameNotFoundException e) {
            // When it doubt, lock it out!
            super.onReceivedSslError(view, handler, error);
        }
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        /* 
         * If you do a document.location.href the url does not get pushed on the stack
         * so we do a check here to see if the url should be pushed.
         */
        if (!this.appView.peekAtUrlStack().equals(url)) {
            this.appView.pushUrl(url);
        }
    }

    /**
     * Sets the authentication token.
     * 
     * @param authenticationToken
     * @param host
     * @param realm
     */
    public void setAuthenticationToken(AuthenticationToken authenticationToken, String host, String realm) {
        if (host == null) {
            host = "";
        }
        if (realm == null) {
            realm = "";
        }
        this.authenticationTokens.put(host.concat(realm), authenticationToken);
    }

    /**
     * Removes the authentication token.
     * 
     * @param host
     * @param realm
     * 
     * @return the authentication token or null if did not exist
     */
    public AuthenticationToken removeAuthenticationToken(String host, String realm) {
        return this.authenticationTokens.remove(host.concat(realm));
    }

    /**
     * Gets the authentication token.
     * 
     * In order it tries:
     * 1- host + realm
     * 2- host
     * 3- realm
     * 4- no host, no realm
     * 
     * @param host
     * @param realm
     * 
     * @return the authentication token
     */
    public AuthenticationToken getAuthenticationToken(String host, String realm) {
        AuthenticationToken token = null;
        token = this.authenticationTokens.get(host.concat(realm));

        if (token == null) {
            // try with just the host
            token = this.authenticationTokens.get(host);

            // Try the realm
            if (token == null) {
                token = this.authenticationTokens.get(realm);
            }

            // if no host found, just query for default
            if (token == null) {
                token = this.authenticationTokens.get("");
            }
        }

        return token;
    }

    /**
     * Clear all authentication tokens.
     */
    public void clearAuthenticationTokens() {
        this.authenticationTokens.clear();
    }

}
