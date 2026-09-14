package com.bayan.live;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.*;
import android.view.WindowManager;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.translation.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private WebView web;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private TranslationRecognizer recognizer;
    private SpeechTranslationConfig config;
    private AudioConfig audio;
    private ScheduledFuture<?> refresh;
    private volatile boolean foreground;
    private boolean permissionInFlight;
    private String pendingUrl,pendingAccess,pendingLocale;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        web=new WebView(this);
        web.setBackgroundColor(0xfff4f6f5);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(!r.getUrl().toString().equals("file:///android_asset/index.html"))
                    return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                return null;
            }
        });
        web.addJavascriptInterface(new Bridge(),"Android");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v,insets)->{ v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        web.loadUrl("file:///android_asset/index.html");
    }
    @Override protected void onResume(){super.onResume();foreground=true;if(!permissionInFlight&&pendingAccess!=null&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)beginPending();}
    @Override protected void onPause(){foreground=false;if(!permissionInFlight)stopSession();super.onPause();}
    @Override protected void onDestroy(){
        stopSession();io.shutdown();web.removeJavascriptInterface("Android");web.destroy();super.onDestroy();
    }
    private JSONObject data(String key,Object value){JSONObject j=new JSONObject();try{j.put(key,value);}catch(Exception ignored){}return j;}
    private void event(String name,JSONObject payload){runOnUiThread(()->{
        if(!isDestroyed()) web.evaluateJavascript("window.bayanEvent&&window.bayanEvent("+JSONObject.quote(name)+","+payload+")",null);
    });}
    private void eventFor(int g,String name,JSONObject payload){runOnUiThread(()->{
        if(g!=generation.get()||isDestroyed())return;
        if(name.equals("listening")){
            if(!foreground)return;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        event(name,payload);
    });}
    private void failure(String message){event("error",data("message",message));}
    private void keepAwake(boolean on){runOnUiThread(()->{if(on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});}
    public class Bridge {
        @JavascriptInterface public void start(String url,String access,String locale){runOnUiThread(()->{
            if(!foreground)return;
            try{
                URI u=new URI(url);
                if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new Exception();
                if(access.length()<32||access.indexOf('\n')>=0||access.indexOf('\r')>=0)throw new Exception();
                if(!locale.equals("ar-SA")&&!locale.equals("ar-QA")&&!locale.equals("ar-EG"))throw new Exception();
            }catch(Exception e){failure("تحقق من عنوان HTTPS ورمز الوصول (32 حرفًا على الأقل).");return;}
            pendingUrl=url;pendingAccess=access;pendingLocale=locale;
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){permissionInFlight=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},70);return;}
            beginPending();
        });}
        @JavascriptInterface public void stop(){runOnUiThread(()->stopSession());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==70){permissionInFlight=false;if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){if(foreground)beginPending();}else{pendingAccess=null;failure("السماح بالميكروفون مطلوب لالتقاط صوت الإمام. يمكنك استخدام العرض التوضيحي.");}}
    }
    private void beginPending(){
        if(pendingAccess==null)return;
        final String url=pendingUrl,access=pendingAccess,locale=pendingLocale;
        pendingAccess=null;
        final int g=generation.incrementAndGet();
        io.execute(()->{
            cleanup();
            try{
                JSONObject token=fetchToken(url,access);
                if(g!=generation.get()||!foreground)return;
                config=SpeechTranslationConfig.fromAuthorizationToken(token.getString("token"),token.getString("region"));
                config.setSpeechRecognitionLanguage(locale);config.addTargetLanguage("ur");
                audio=AudioConfig.fromDefaultMicrophoneInput();
                recognizer=new TranslationRecognizer(config,audio);
                recognizer.recognizing.addEventListener((s,e)->caption(g,e.getResult(),false));
                recognizer.recognized.addEventListener((s,e)->{
                    if(e.getResult().getReason()==ResultReason.TranslatedSpeech)caption(g,e.getResult(),true);
                });
                recognizer.canceled.addEventListener((s,e)->failSession(g,"انقطع الاتصال بخدمة الترجمة. تحقق من الشبكة وإعداد الخدمة ثم أعد البدء."));
                recognizer.sessionStopped.addEventListener((s,e)->failSession(g,"انتهت جلسة الخدمة. اضغط بدء الترجمة للاتصال مجددًا."));
                recognizer.startContinuousRecognitionAsync().get(20,TimeUnit.SECONDS);
                if(g!=generation.get()){cleanup();return;}
                eventFor(g,"listening",new JSONObject());
                refresh=io.scheduleAtFixedRate(()->{
                    if(g!=generation.get())return;
                    try{JSONObject t=fetchToken(url,access);if(g==generation.get()&&recognizer!=null)recognizer.setAuthorizationToken(t.getString("token"));}
                    catch(Exception e){failSession(g,"تعذّر تجديد الاتصال. تحقق من الشبكة ثم أعد بدء الجلسة.");}
                },8,8,TimeUnit.MINUTES);
            }catch(Exception e){if(g==generation.get()){failSession(g,"تعذّر بدء الترجمة. تحقق من الإنترنت ورمز الوصول وإعداد Azure Speech.");}}
        });
    }
    private JSONObject fetchToken(String endpoint,String access)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestMethod("POST");
        c.setRequestProperty("Authorization","Bearer "+access);c.setRequestProperty("Accept","application/json");
        try{
            if(c.getResponseCode()!=200)throw new IOException("Token service rejected request");
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[2048];int n;
            try(InputStream input=c.getInputStream()){while((n=input.read(chunk))!=-1){if(b.size()+n>32768)throw new IOException("Response too large");b.write(chunk,0,n);}}
            JSONObject j=new JSONObject(new String(b.toByteArray(),StandardCharsets.UTF_8));
            if(j.getString("token").isEmpty()||!j.getString("region").matches("[a-z0-9]+"))throw new IOException("Invalid response");
            return j;
        }finally{c.disconnect();}
    }
    private void caption(int g,TranslationRecognitionResult r,boolean complete){
        if(g!=generation.get())return;
        JSONObject j=new JSONObject();try{j.put("ar",r.getText());j.put("ur",r.getTranslations().get("ur"));j.put("final",complete);}catch(Exception ignored){}
        eventFor(g,"caption",j);
    }
    private void failSession(int g,String message){
        if(!generation.compareAndSet(g,g+1))return;
        final int failedGeneration=g+1;
        runOnUiThread(()->{if(generation.get()==failedGeneration){keepAwake(false);failure(message);}});
        if(!io.isShutdown())io.execute(()->{if(generation.get()==failedGeneration)cleanup();});
    }
    private void stopSession(){
        generation.incrementAndGet();if(!permissionInFlight)pendingAccess=null;keepAwake(false);
        if(!io.isShutdown())io.execute(()->cleanup());
        event("stopped",new JSONObject());
    }
    // SDK object lifecycle is serialized on the worker executor, never on its callback threads.
    private void cleanup(){
        if(refresh!=null){refresh.cancel(false);refresh=null;}
        if(recognizer!=null){try{recognizer.stopContinuousRecognitionAsync().get(5,TimeUnit.SECONDS);}catch(Exception ignored){}try{recognizer.close();}catch(Exception ignored){}recognizer=null;}
        if(audio!=null){audio.close();audio=null;}
        if(config!=null){config.close();config=null;}
    }
}
