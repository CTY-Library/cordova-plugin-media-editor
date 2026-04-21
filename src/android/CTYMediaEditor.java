package org.apache.cordova.CTYMediaEditor;

import android.Manifest;
import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import android.graphics.Bitmap;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.linkedin.android.litr.MediaTransformer;
import com.linkedin.android.litr.MimeType;
import com.linkedin.android.litr.TrackTransform;
import com.linkedin.android.litr.TransformationListener;
import com.linkedin.android.litr.TransformationOptions;
import com.linkedin.android.litr.analytics.TrackTransformationInfo;
import com.linkedin.android.litr.codec.MediaCodecDecoder;
import com.linkedin.android.litr.codec.PassthroughBufferEncoder;
import com.linkedin.android.litr.io.MediaExtractorMediaSource;
import com.linkedin.android.litr.io.MediaRange;
import com.linkedin.android.litr.io.WavMediaTarget;
import com.linkedin.android.litr.render.AudioRenderer; 


/**
 * CTYMediaEditor plugin for Android
 * Created by Ross Martin 2-2-15
 */
public class CTYMediaEditor extends CordovaPlugin {

    private static final String TAG = "CTYMediaEditor";
    private static final int REQUEST_CODE_READ_VIDEO = 4101;
    private static final int REQUEST_CODE_READ_AUDIO = 4102;
    private static final int REQUEST_CODE_WRITE_SETTINGS = 4103;
    private static final int REQUEST_CODE_MANAGE_EXTERNAL_STORAGE = 4104;

    private CallbackContext callback;
    private CallbackContext pendingCallback;
    private String pendingAction;
    private JSONArray pendingArgs;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute method starting");

        this.callback = callbackContext;

        if (requiresReadPermission(action) && !hasReadPermission(action)) {
            cachePendingRequest(action, args, callbackContext);
            requestReadPermission(action);
            return true;
        }

        if (requiresReadPermission(action) && !hasSpecialPermissions()) {
            cachePendingRequest(action, args, callbackContext);
            requestSpecialPermission();
            return true;
        }

        if (action.equals("transcodeVideo")) {
            try {
                this.transcodeVideo(args);
            } catch (Throwable e) {
                Log.e(TAG, "transcodeVideo failed", e);
                callback.error(e.toString());
            }
            return true;
        } if (action.equals("transcodeAudio")) {
            try {
                this.transcodeAudio(args);
            } catch (Throwable e) {
                Log.e(TAG, "transcodeAudio failed", e);
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("createThumbnail")) {
            try {
                this.createThumbnail(args);
            } catch (Throwable e) {
                Log.e(TAG, "createThumbnail failed", e);
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("getVideoInfo")) {
            try {
                this.getVideoInfo(args);
            } catch (Throwable e) {
                Log.e(TAG, "getVideoInfo failed", e);
                callback.error(e.toString());
            }
            return true;
        }

        return false;
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        try {
            super.onRequestPermissionResult(requestCode, permissions, grantResults);
        } catch (Throwable ignore) {
            Log.w(TAG, "super.onRequestPermissionResult failed", ignore);
        }

        if (requestCode != REQUEST_CODE_READ_VIDEO && requestCode != REQUEST_CODE_READ_AUDIO) {
            return;
        }

        CallbackContext cb = pendingCallback != null ? pendingCallback : callback;
        if (cb == null) {
            clearPendingRequest();
            Log.e(TAG, "Permission callback lost, pending callback is null");
            return;
        }

        if (cordova == null || cordova.getActivity() == null || cordova.getActivity().isFinishing()) {
            clearPendingRequest();
            cb.error("授权后页面已失效，请重试");
            return;
        }
        this.callback = cb;

        if (grantResults == null || grantResults.length == 0) {
            clearPendingRequest();
            cb.error("读取媒体文件权限被拒绝");
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                clearPendingRequest();
                cb.error("读取媒体文件权限被拒绝");
                return;
            }
        }

        if (pendingAction == null || pendingArgs == null) {
            clearPendingRequest();
            return;
        }

        String action = pendingAction;
        JSONArray args = pendingArgs;
        clearPendingRequest();
        try {
            executePendingAction(action, args);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to execute pending action after permission grant", e);
            cb.error("授权后恢复执行失败: " + e.getMessage());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode != REQUEST_CODE_WRITE_SETTINGS && requestCode != REQUEST_CODE_MANAGE_EXTERNAL_STORAGE) {
            return;
        }

        CallbackContext cb = pendingCallback != null ? pendingCallback : callback;
        if (cb == null) {
            clearPendingRequest();
            Log.e(TAG, "ActivityResult callback lost, pending callback is null");
            return;
        }

        if (!hasSpecialPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(cordova.getActivity())) {
                cb.error("请开启系统设置修改权限");
            } else {
                cb.error("请开启所有文件访问权限");
            }
            clearPendingRequest();
            return;
        }

        if (pendingAction == null || pendingArgs == null) {
            clearPendingRequest();
            return;
        }

        String action = pendingAction;
        JSONArray args = pendingArgs;
        clearPendingRequest();
        this.callback = cb;
        try {
            executePendingAction(action, args);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to execute pending action after special permission grant", e);
            cb.error("授权后恢复执行失败: " + e.getMessage());
        }
    }

    private void cachePendingRequest(String action, JSONArray args, CallbackContext callbackContext) {
        this.pendingAction = action;
        this.pendingArgs = args;
        this.callback = callbackContext;
        this.pendingCallback = callbackContext;

        PluginResult pendingResult = new PluginResult(PluginResult.Status.NO_RESULT);
        pendingResult.setKeepCallback(true);
        callbackContext.sendPluginResult(pendingResult);
    }

    private void clearPendingRequest() {
        this.pendingAction = null;
        this.pendingArgs = null;
        this.pendingCallback = null;
    }

    private boolean requiresReadPermission(String action) {
        return action.equals("transcodeVideo")
                || action.equals("transcodeAudio")
                || action.equals("createThumbnail")
                || action.equals("getVideoInfo");
    }

    private boolean hasReadPermission(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return PermissionHelper.hasPermission(this, getReadPermissionForAction(action));
        }
        return PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private void requestReadPermission(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String permission = getReadPermissionForAction(action);
            int requestCode = action.equals("transcodeAudio") ? REQUEST_CODE_READ_AUDIO : REQUEST_CODE_READ_VIDEO;
            PermissionHelper.requestPermission(this, requestCode, permission);
        } else {
            PermissionHelper.requestPermission(this, REQUEST_CODE_READ_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private String getReadPermissionForAction(String action) {
        if (action.equals("transcodeAudio")) {
            return Manifest.permission.READ_MEDIA_AUDIO;
        }
        return Manifest.permission.READ_MEDIA_VIDEO;
    }

    private boolean hasSpecialPermissions() {
        return hasWriteSettingsPermission() && hasManageExternalStoragePermission();
    }

    private boolean hasWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.System.canWrite(cordova.getActivity());
    }

    private boolean hasManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        return Environment.isExternalStorageManager();
    }

    private void requestSpecialPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasWriteSettingsPermission()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + cordova.getActivity().getPackageName()));
            cordova.startActivityForResult(this, intent, REQUEST_CODE_WRITE_SETTINGS);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasManageExternalStoragePermission()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + cordova.getActivity().getPackageName()));
            cordova.startActivityForResult(this, intent, REQUEST_CODE_MANAGE_EXTERNAL_STORAGE);
        }
    }

    private void executePendingAction(String action, JSONArray args) throws JSONException {
        try {
            if (action.equals("transcodeVideo")) {
                this.transcodeVideo(args);
            } else if (action.equals("transcodeAudio")) {
                this.transcodeAudio(args);
            } else if (action.equals("createThumbnail")) {
                this.createThumbnail(args);
            } else if (action.equals("getVideoInfo")) {
                this.getVideoInfo(args);
            }
        } catch (IOException e) {
            callback.error(e.toString());
        }
    }

    private void transcodeAudioToWav(File inFile, String outputFilePath, boolean deleteInputFile,int sampleRate,int channelCount,int audioBitrate,long startMillSeconds, long endMillSeconds ,  Runnable successCallback)  throws JSONException, IOException {
        MediaRange mediaRange = new MediaRange(
            TimeUnit.MILLISECONDS.toMicros((Math.max(startMillSeconds, 0) * 1000)),
            TimeUnit.MILLISECONDS.toMicros(endMillSeconds>0?endMillSeconds* 1000:Long.MAX_VALUE));

        try {
            WavMediaTarget mediaTarget = new WavMediaTarget(outputFilePath);
            MediaExtractorMediaSource mediaSource = new MediaExtractorMediaSource(this.cordova.getContext(), Uri.fromFile(inFile), mediaRange);
            List<TrackTransform> trackTransforms = new ArrayList<TrackTransform>();
            for(int i=0;i<mediaSource.getTrackCount();i++){
                MediaFormat trackFormat = mediaSource.getTrackFormat(i);
                if(trackFormat.getString(MediaFormat.KEY_MIME).startsWith("audio/")){
                    if(audioBitrate==0) {
                        MediaMetadataRetriever m = new MediaMetadataRetriever();
                        m.setDataSource(inFile.getAbsolutePath());
                        audioBitrate = Integer.valueOf(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                    }
                    sampleRate = sampleRate==0?trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE):sampleRate;//获取比特
                    channelCount = 1;//  channelCount==0? trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channelCount;//获取声道数量
                    MediaFormat mediaFormat = MediaFormat.createAudioFormat(
                            MimeType.AUDIO_RAW,
                            sampleRate,
                            channelCount
                    );
                    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
                    PassthroughBufferEncoder encoder = new PassthroughBufferEncoder(8192);
                    TrackTransform trackTransform = new TrackTransform.Builder(
                                    mediaSource,
                                    i,
                                    mediaTarget
                            )
                            .setTargetTrack(0)
                            .setDecoder(new MediaCodecDecoder())
                            .setEncoder(encoder)
                            .setRenderer(new AudioRenderer(encoder))
                            .setTargetFormat(mediaFormat)
                            .build();
                    trackTransforms.add(trackTransform);
                    break;

                }
            }

            MediaTransformer mediaTransformer = new MediaTransformer(cordova.getContext());
            TransformationListener transformationListener = getTransformationListener(false,mediaTransformer, () -> {
                if(deleteInputFile){
                    // 删除原文件
                    inFile.delete();
                    Log.d(TAG, "delete inFile:"+inFile.getAbsolutePath());
                }
                successCallback.run();
            });
            mediaTransformer.transform(
                UUID.randomUUID().toString(),
                trackTransforms,
                transformationListener,
                MediaTransformer.GRANULARITY_DEFAULT
            );
        } catch (Throwable ex) {
            Log.d(TAG, "Exception when trying to transcode audio", ex);
        }
    }

    private void transcodeAudio(JSONArray args) throws JSONException, IOException {

        JSONObject options = args.optJSONObject(0);
        Log.d(TAG, "options: " + options.toString());

        boolean deleteInputFile = options.optBoolean("deleteInputFile", false);
        int sampleRate = options.optInt("sampleRate", 44100); //样本率
        int channelCount = options.optInt("channelCount", 2); //通道
        int audioBitrate =  options.optInt("audioBitrate", 64*1024); //比特率
        String outputFileType = options.optString("outputFileType", "MP3").toUpperCase();
        File inFile = this.resolveLocalFileSystemURI(options.getString("fileUri"));

        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input audio does not exist.");
            return;
        }
        if(!outputFileType.equals("M4A")){
            outputFileType = "MP3";
        }
       String outputFilePath = getOutputFile(options, "audio", outputFileType.equals("MP3")?".mp3":".m4a");
       if(outputFilePath.equals("")){
            return;
       }
        if(outputFileType=="MP3") {
            String wavTempFilePath = getOutputFile(options, "audio", ".wav");
            if(wavTempFilePath.equals("")){
                return;
            }
            transcodeAudioToWav(inFile, wavTempFilePath, deleteInputFile, sampleRate, channelCount, audioBitrate, 0, 0, () -> {
                try {
                    transcodeWavToMp3(wavTempFilePath, outputFilePath, sampleRate, channelCount, audioBitrate);
                    File wavFile = new File(wavTempFilePath);
                    wavFile.delete();
                    logOutputFileResult(outputFilePath, "transcodeAudio-mp3");
                    notifyMediaLibrary(outputFilePath, "audio/mpeg");
                    callback.success(outputFilePath);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        else {
            transcodeAudioToM4a( inFile,  outputFilePath,  sampleRate,  channelCount,  audioBitrate,   () -> {
                if (deleteInputFile) {
                    // 删除原文件
                    inFile.delete();
                    Log.d(TAG, "delete inFile:" + inFile.getAbsolutePath());
                }
                logOutputFileResult(outputFilePath, "transcodeAudio-m4a");
                notifyMediaLibrary(outputFilePath, "audio/mp4");
                callback.success(outputFilePath);
            });
        }
    }

    private  void transcodeAudioToM4a( File inFile,String outputFilePath,int sampleRate,int channelCount,int audioBitrate,   Runnable successCallback) throws IOException {


        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    // 指定编码器颜色格式
                    MediaFormat targetVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 0, 0);
                    targetVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    // 指定帧率
                    targetVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
                    // 指定比特率
                    targetVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1000000);
                    //指定关键帧时间间隔，一般设置为每秒关键帧
                    targetVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                    MediaFormat targetAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate, channelCount);
                    targetAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);

                    MediaTransformer mediaTransformer = new MediaTransformer(cordova.getContext());
                    TransformationListener listener = getTransformationListener(true,mediaTransformer,new Runnable() {
                        public void run() {
                            successCallback.run();
                        }
                    });

                    TransformationOptions opt = null;// new TransformationOptions(1,null,null,null,false,false); //todo 参数设置 ?
                    mediaTransformer.transform(UUID.randomUUID().toString(), Uri.fromFile(inFile),outputFilePath,targetVideoFormat,targetAudioFormat,listener,opt);

                } catch (Throwable e) {
                    Log.d(TAG, "transcode exception ", e);
                    callback.error(e.toString());
                }
            }
        });
    }

    private void transcodeWavToMp3(String inputFilePath,String outputFilePath,int sampleRate,int channelCount,int audioBitrate) throws IOException {

        File inputFile = new File(inputFilePath);
        File outputFile = new File(outputFilePath);
        FileInputStream  inFileStream = new FileInputStream(inputFile);
        FileOutputStream outFileStream = new FileOutputStream(outputFile);

       byte[]  inBuffer = new  byte[8192*2];
       byte[]  outBuffer = new  byte[(int)(7200 + (1.25 * 8192))];
       int readCount=0;
       int writeCount =0;
       short[] inShortBuffer = new short[8192];
       try {

           int result = Mp3LameWraper.init(sampleRate, channelCount, sampleRate, audioBitrate/1000,9);
           if (result < 0) {
               Log.d(TAG, "init fail Mp3LameWraper:" + result);
           }
           do {
               readCount = inFileStream.read(inBuffer);
               if (readCount <= 0) {
                   writeCount = Mp3LameWraper.flush(outBuffer);
               } else {
                  byteArray2ShortArray(inBuffer,inShortBuffer );
                  writeCount = Mp3LameWraper.encode(inShortBuffer, inShortBuffer, readCount / 2, outBuffer);
               }

               if (writeCount > 0) {
                   outFileStream.write(outBuffer, 0, writeCount);
               }
           }
           while (readCount >0);
           Mp3LameWraper.close();

           inFileStream.close();
           outFileStream.flush();
           outFileStream.close();
       }
       catch(Throwable e) {
           Log.d(TAG, "transcodeWavToMp3 exception ", e);
           Mp3LameWraper.close();
           inFileStream.close();
           outFileStream.close();
       }

    }

    // byte数组转short数组
    private static  void byteArray2ShortArray(byte[] data,short[] inShortBuffer  )
    {
       for (int i = 0; i < inShortBuffer.length ;   i++)
                inShortBuffer[i] = (short) ((data[i * 2] & 0xff) | (data[i * 2 + 1] & 0xff) << 8);
    }


    private void transcodeVideo(JSONArray args) throws JSONException, IOException {

        JSONObject options = args.optJSONObject(0);
        Log.d(TAG, "options: " + options.toString());

        File inFile = this.resolveLocalFileSystemURI(options.getString("fileUri"));
        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input video does not exist.");
            return;
        }

        final String videoSrcPath = inFile.getAbsolutePath();

        boolean deleteInputFile = options.optBoolean("deleteInputFile", false);
        int width = options.optInt("width", 0);
        int height = options.optInt("height", 0);
        int fps = options.optInt("fps", 24);
        int videoBitrate = options.optInt("videoBitrate", 1000000); // default to 1 megabit
        long videoDuration = options.optLong("duration", 1000 * 1000);

        int sampleRate = options.optInt("sampleRate", 44100); //样本率
        int channelCount = options.optInt("channelCount", 2); //通道
        int audioBitrate =  options.optInt("audioBitrate", 64*1024); //比特率

        Log.d(TAG, "videoSrcPath: " + videoSrcPath);

        String outputFilePath = getOutputFile(options, "video", ".mp4");
        if(outputFilePath.equals("")){
            return;
        }

        boolean maintainAspectRatio = options.optBoolean("maintainAspectRatio", true); //比特率

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(videoSrcPath);
        //To DO: 竖屏的宽和高?
        int videoWidth = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int videoHeight = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        if(width > 0 && height> 0 && maintainAspectRatio || (width > 0 && height == 0)){
            height = width * videoHeight / videoWidth;
        }
        else if(width == 0 && height > 0){
            width = height * videoWidth / videoHeight;
        }
        else if(width == 0 && height == 0){
            if(videoWidth > videoHeight){
                width = Math.min(videoWidth, 1920);
                height = width * videoHeight / videoWidth;
            }
            else{
                height = Math.min(videoHeight, 1920);
                width = height * videoWidth / videoHeight;
            }
        }


        final int finalWidth = width + width % 2;
        final  int finalHeight =height + height % 2;
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
            try {
                  // 指定编码器颜色格式
                  MediaFormat targetVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, finalWidth, finalHeight);
                  targetVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                  // 指定帧率
                  targetVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                  // 指定比特率
                  targetVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
                  //指定关键帧时间间隔，一般设置为每秒关键帧
                  targetVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                  MediaFormat targetAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate, channelCount);
                  targetAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);

                  MediaTransformer mediaTransformer = new MediaTransformer(cordova.getContext());
                    TransformationListener listener = getTransformationListener(true,mediaTransformer,new Runnable() {
                        public void run() {
                            if (deleteInputFile) {
                                // 删除原文件
                                inFile.delete();
                                Log.d(TAG, "delete inFile:" + inFile.getAbsolutePath());
                            }
                            logOutputFileResult(outputFilePath, "transcodeVideo");
                            notifyMediaLibrary(outputFilePath, "video/mp4");
                            callback.success(outputFilePath);
                        }
                    });

                  TransformationOptions opt = null;// new TransformationOptions(1,null,null,null,false,false); //todo 参数设置 ?
                  mediaTransformer.transform(UUID.randomUUID().toString(), Uri.fromFile(inFile),outputFilePath,targetVideoFormat,targetAudioFormat,listener,opt);

                  //PluginResult progressResult = new PluginResult(PluginResult.Status.OK, outputFilePath);
                  //progressResult.setKeepCallback(true);
                  //callback.sendPluginResult(progressResult);
                  } catch (Throwable e) {
                      Log.d(TAG, "transcode exception ", e);
                      callback.error(e.toString());
                  }
              }
        });
    }

    private String getOutputFile(JSONObject options, String mediaType, String outputExtension){
        final String outputFileName = options.optString("outputFileName", new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date()));
        final Context appContext = cordova.getActivity().getApplicationContext();
        final PackageManager pm = appContext.getPackageManager();

        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(cordova.getActivity().getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            ai = null;
        }
        final String appName = (String) (ai != null ? pm.getApplicationLabel(ai) : "Unknown");
        final boolean saveToLibrary = options.optBoolean("saveToLibrary", true);
        File mediaStorageDir;

        // 视频输出到DCIM/Camera以获得更好的相册兼容性，音频直接输出到公共Music目录。
        if (mediaType.equals("video") || mediaType.equals("audio") || saveToLibrary) {
            if (mediaType.equals("video")) {
                mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
            } else if (mediaType.equals("audio")) {
                mediaStorageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            } else {
                mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), appName);
            }
        } else {
            mediaStorageDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + cordova.getActivity().getPackageName()
            + (mediaType.equals("video")?"/files/videos":"/files/audios"));
        }

        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                callback.error("Can't access or make output directory");
                return "";
            }
        }

        String outputFilePath = new File(mediaStorageDir.getPath(), outputFileName + outputExtension).getAbsolutePath();
        Log.d(TAG, "outputFilePath: " + outputFilePath);
        return outputFilePath;
    }

    private void logOutputFileResult(String outputFilePath, String scene) {
        try {
            File outputFile = new File(outputFilePath);
            Log.i(TAG, "Output file check [" + scene + "] exists=" + outputFile.exists()
                    + ", size=" + outputFile.length()
                    + ", canRead=" + outputFile.canRead()
                    + ", path=" + outputFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "Output file check failed [" + scene + "]: " + outputFilePath, t);
        }
    }

    private void notifyMediaLibrary(String outputFilePath, String mimeType) {
        try {
            MediaScannerConnection.scanFile(
                    cordova.getContext(),
                    new String[]{outputFilePath},
                    new String[]{mimeType},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            Log.i(TAG, "Media scan completed: path=" + path + ", uri=" + uri);
                        }
                    }
            );
            Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scanIntent.setData(Uri.fromFile(new File(outputFilePath)));
            cordova.getContext().sendBroadcast(scanIntent);
        } catch (Throwable t) {
            Log.w(TAG, "Media scan failed: " + outputFilePath, t);
        }
    }

    private TransformationListener getTransformationListener(boolean outputProgress, MediaTransformer mediaTransformer,  Runnable successCallback){
        TransformationListener videoTransformationListener = new TransformationListener() {
            @Override
            public void onStarted(@NonNull String id) {
                Log.d(TAG, "TransformationListener onStarted");
            }

            @Override
            public void onProgress(@NonNull String id, float progress) {
                Log.d(TAG, "TransformationListener onProgress");
                if(outputProgress){
                    JSONObject result = new JSONObject(); //返回到不同的js回调函数
                    try {
                        result.put("progress",progress*100);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                    PluginResult progressResult = new PluginResult(PluginResult.Status.OK,  result);
                    progressResult.setKeepCallback(true);
                    callback.sendPluginResult(progressResult);

                }
            }

            @Override
            public void onCompleted(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
                mediaTransformer.release();
                successCallback.run();
                Log.d(TAG, "TransformationListener onCompleted");
            }

            @Override
            public void onCancelled(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {

            }

            @Override
            public void onError(@NonNull String id, @Nullable Throwable cause, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
                mediaTransformer.release();
                Log.d(TAG, "TransformationListener onError");
                callback.error("error");
            }
        };
        return videoTransformationListener;
    }


    /**
     * createThumbnail
     *
     * Creates a thumbnail from the start of a video.
     *
     * ARGUMENTS
     * =========
     * fileUri        - input file path
     * outputFileName - output file name
     * atTime         - location in the video to create the thumbnail (in seconds)
     * width          - width for the thumbnail (optional)
     * height         - height for the thumbnail (optional)
     * quality        - quality of the thumbnail (optional, between 1 and 100)
     *
     * RESPONSE
     * ========
     *
     * outputFilePath - path to output file
     *
     * @param   args
     * @return void
     */
    private void createThumbnail(JSONArray args) throws JSONException, IOException {
        Log.d(TAG, "createThumbnail firing");


        JSONObject options = args.optJSONObject(0);
        Log.d(TAG, "options: " + options.toString());

        String fileUri = options.getString("fileUri");
        if (!fileUri.startsWith("file:/")) {
            fileUri = "file:/" + fileUri;
        }

        File inFile = this.resolveLocalFileSystemURI(fileUri);
        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input video does not exist.");
            return;
        }
        final String srcVideoPath = inFile.getAbsolutePath();
        String outputFileName = options.optString(
                "outputFileName",
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date())
        );

        final int quality = options.optInt("quality", 100);
        final int width = options.optInt("width", 0);
        final int height = options.optInt("height", 0);
        long atTimeOpt = options.optLong("atTime", 0);
        final long atTime = (atTimeOpt == 0) ? 0 : atTimeOpt * 1000000;

        final Context appContext = cordova.getActivity().getApplicationContext();
        PackageManager pm = appContext.getPackageManager();

        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(cordova.getActivity().getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            ai = null;
        }
        final String appName = (String) (ai != null ? pm.getApplicationLabel(ai) : "Unknown");

        File externalFilesDir =  new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + cordova.getActivity().getPackageName() + "/files/files/videos");

        if (!externalFilesDir.exists()) {
            if (!externalFilesDir.mkdirs()) {
                callback.error("Can't access or make Movies directory");
                return;
            }
        }

        final File outputFile =  new File(
                externalFilesDir.getPath(),
                outputFileName + ".jpg"
        );
        final String outputFilePath = outputFile.getAbsolutePath();

        // start task
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

                OutputStream outStream = null;

                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(srcVideoPath);

                    Bitmap bitmap = mmr.getFrameAtTime(atTime);

                    if (width > 0 || height > 0) {
                        int videoWidth = bitmap.getWidth();
                        int videoHeight = bitmap.getHeight();
                        double aspectRatio = (double) videoWidth / (double) videoHeight;

                        Log.d(TAG, "videoWidth: " + videoWidth);
                        Log.d(TAG, "videoHeight: " + videoHeight);

                        int scaleWidth = Double.valueOf(height * aspectRatio).intValue();
                        int scaleHeight = Double.valueOf(scaleWidth / aspectRatio).intValue();

                        Log.d(TAG, "scaleWidth: " + scaleWidth);
                        Log.d(TAG, "scaleHeight: " + scaleHeight);

                        final Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, scaleWidth, scaleHeight, false);
                        bitmap.recycle();
                        bitmap = resizedBitmap;
                    }

                    outStream = new FileOutputStream(outputFile);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream);

                    callback.success(outputFilePath);

                } catch (Throwable e) {
                    if (outStream != null) {
                        try {
                            outStream.close();
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                    }

                    Log.d(TAG, "exception on thumbnail creation", e);
                    callback.error(e.toString());

                }

            }
        });
    }

    /**
     * getVideoInfo
     *
     * Gets info on a video
     *
     * ARGUMENTS
     * =========
     *
     * fileUri:      - path to input video
     *
     * RESPONSE
     * ========
     *
     * width         - width of the video
     * height        - height of the video
     * orientation   - orientation of the video
     * duration      - duration of the video (in seconds)
     * size          - size of the video (in bytes)
     * bitrate       - bitrate of the video (in bits per second)
     *
     * @param   args
     * @return void
     */
    private void getVideoInfo(JSONArray args) throws JSONException, IOException {
        Log.d(TAG, "getVideoInfo firing");

        JSONObject options = args.optJSONObject(0);
        Log.d(TAG, "options: " + options.toString());

        File inFile = this.resolveLocalFileSystemURI(options.getString("fileUri"));
        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input video does not exist.");
            return;
        }

        String videoSrcPath = inFile.getAbsolutePath();
        Log.d(TAG, "videoSrcPath: " + videoSrcPath);

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(videoSrcPath);
        float videoWidth = Float.parseFloat(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        float videoHeight = Float.parseFloat(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));

        String orientation;
        if (Build.VERSION.SDK_INT >= 17) {
            String mmrOrientation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            Log.d(TAG, "mmrOrientation: " + mmrOrientation); // 0, 90, 180, or 270

            if (videoWidth < videoHeight) {
                if (mmrOrientation.equals("0") || mmrOrientation.equals("180")) {
                    orientation = "portrait";
                } else {
                    orientation = "landscape";
                }
            } else {
                if (mmrOrientation.equals("0") || mmrOrientation.equals("180")) {
                    orientation = "landscape";
                } else {
                    orientation = "portrait";
                }
            }
        } else {
            orientation = (videoWidth < videoHeight) ? "portrait" : "landscape";
        }

        double duration = Double.parseDouble(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
        long bitrate = Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));

        JSONObject response = new JSONObject();
        response.put("width", videoWidth);
        response.put("height", videoHeight);
        response.put("orientation", orientation);
        response.put("duration", duration);
        response.put("size", inFile.length());
        response.put("bitrate", bitrate);

        callback.success(response);
    }


    @SuppressWarnings("deprecation")
    private File resolveLocalFileSystemURI(String url) throws IOException, JSONException {
        String decoded = URLDecoder.decode(url, "UTF-8");

        File fp = null;

        // Handle the special case where you get an Android content:// uri.
        if (url.startsWith("content:")) {
            Uri uri = Uri.parse(url);
            fp = resolveContentUriToFile(uri);
            if (fp == null) {
                fp = copyContentUriToCache(uri);
            }
        } else {
            // Test to see if this is a valid URL first
            //@SuppressWarnings("unused")
            //URL testUrl = new URL(decoded);

            if (decoded.startsWith("file://")) {
                int questionMark = decoded.indexOf("?");
                if (questionMark < 0) {
                    fp = new File(decoded.substring(7, decoded.length()));
                } else {
                    fp = new File(decoded.substring(7, questionMark));
                }
            } else if (decoded.startsWith("file:/")) {
                fp = new File(decoded.substring(6, decoded.length()));
            } else {
                fp = new File(decoded);
            }
        }

        if (fp == null || !fp.exists()) {
            String resolvedPath = fp == null ? "null" : fp.getAbsolutePath();
            throw new FileNotFoundException("" + url + " -> " + resolvedPath);
        }
        if (!fp.canRead()) {
            throw new IOException("can't read file: " + url + " -> " + fp.getCanonicalPath());
        }
        return fp;
    }

    private File resolveContentUriToFile(Uri uri) {
        File externalFile = resolveExternalStorageDocumentFile(uri);
        if (externalFile != null) {
            Log.d(TAG, "Resolved external document uri to file: " + externalFile.getAbsolutePath());
            return externalFile;
        }

        Context context = this.cordova.getActivity().getApplicationContext();
        String path;
        try {
            path = getPath(context, uri);
        } catch (Throwable e) {
            Log.w(TAG, "resolveContentUriToFile failed: " + uri, e);
            return null;
        }
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        File file = new File(path);
        return file.exists() ? file : null;
    }

    private File resolveExternalStorageDocumentFile(Uri uri) {
        if (!isExternalStorageDocument(uri)) {
            return null;
        }

        String docId = extractDocumentId(uri);
        if (docId == null || docId.length() == 0) {
            return null;
        }

        String[] split = docId.split(":", 2);
        if (split.length < 2) {
            return null;
        }

        String type = split[0];
        String relativePath = split[1];
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        List<String> candidates = new ArrayList<String>();
        if ("primary".equalsIgnoreCase(type)) {
            candidates.add(Environment.getExternalStorageDirectory() + "/" + relativePath);
            candidates.add("/storage/emulated/0/" + relativePath);
            candidates.add("/sdcard/" + relativePath);
        } else if ("home".equalsIgnoreCase(type)) {
            candidates.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/" + relativePath);
            candidates.add("/storage/emulated/0/Documents/" + relativePath);
            candidates.add("/sdcard/Documents/" + relativePath);
        }

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.exists()) {
                return file;
            }
        }

        return null;
    }

    private String extractDocumentId(Uri uri) {
        String docId = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(cordova.getActivity(), uri)) {
                docId = DocumentsContract.getDocumentId(uri);
            }
        } catch (Throwable ignored) {
        }

        if (docId == null || docId.length() == 0) {
            String uriString = uri.toString();
            int markerIndex = uriString.indexOf("/document/");
            if (markerIndex >= 0 && markerIndex + 10 < uriString.length()) {
                docId = uriString.substring(markerIndex + 10);
            }
        }

        if (docId == null || docId.length() == 0) {
            String lastSegment = uri.getLastPathSegment();
            if (lastSegment != null && lastSegment.length() > 0) {
                docId = lastSegment;
            }
        }

        if (docId == null || docId.length() == 0) {
            return null;
        }

        try {
            docId = URLDecoder.decode(docId, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
        }

        if (docId.startsWith("document/")) {
            docId = docId.substring("document/".length());
        }
        return docId;
    }

    private File copyContentUriToCache(Uri uri) throws IOException {
        Context context = this.cordova.getActivity().getApplicationContext();
        File cacheDir = new File(context.getCacheDir(), "cty-media-editor");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Can't create cache dir: " + cacheDir.getAbsolutePath());
        }

        String displayName = getDisplayName(context, uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "media_" + System.currentTimeMillis();
        }

        File cacheFile = createUniqueFile(cacheDir, displayName);
        ContentResolver resolver = context.getContentResolver();

        try (InputStream inputStream = resolver.openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(cacheFile)) {
            if (inputStream == null) {
                throw new FileNotFoundException("Can't open input stream for uri: " + uri);
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        } catch (SecurityException e) {
            throw new IOException("No permission to read uri: " + uri, e);
        }

        return cacheFile;
    }

    private File createUniqueFile(File directory, String fileName) throws IOException {
        String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        File candidate = new File(directory, safeName);
        if (!candidate.exists()) {
            return candidate;
        }

        String name = safeName;
        String extension = "";
        int dotIndex = safeName.lastIndexOf('.');
        if (dotIndex > 0) {
            name = safeName.substring(0, dotIndex);
            extension = safeName.substring(dotIndex);
        }

        int index = 1;
        while (candidate.exists()) {
            candidate = new File(directory, name + "(" + index + ")" + extension);
            index++;
        }
        return candidate;
    }

    private String getDisplayName(Context context, Uri uri) {
        if (isExternalStorageDocument(uri)) {
            String documentId = DocumentsContract.getDocumentId(uri);
            if (documentId != null) {
                int separatorIndex = documentId.indexOf(':');
                if (separatorIndex >= 0 && separatorIndex + 1 < documentId.length()) {
                    String relativePath = documentId.substring(separatorIndex + 1);
                    int slashIndex = relativePath.lastIndexOf('/');
                    return slashIndex >= 0 ? relativePath.substring(slashIndex + 1) : relativePath;
                }
            }
        }

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (columnIndex >= 0) {
                    return cursor.getString(columnIndex);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to query display name for uri: " + uri, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        String lastSegment = uri.getLastPathSegment();
        if (lastSegment == null || lastSegment.trim().isEmpty()) {
            return null;
        }
        int slashIndex = lastSegment.lastIndexOf('/');
        return slashIndex >= 0 ? lastSegment.substring(slashIndex + 1) : lastSegment;
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @author paulburke
     */
    public static String getPath(final Context context, final Uri uri) {

        String externalStoragePath = tryResolveExternalStoragePath(context, uri);
        if (externalStoragePath != null) {
            return externalStoragePath;
        }

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                if ("home".equalsIgnoreCase(type)) {
                    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/" + split[1];
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                if (id == null || id.length() == 0 || id.startsWith("raw:")) {
                    return id != null && id.startsWith("raw:") ? id.replaceFirst("raw:", "") : null;
                }
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[] {
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    public static String getDataColumn(Context context, Uri uri, String selection,
                                       String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to query uri: " + uri, e);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid query for uri: " + uri, e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unexpected error when query uri: " + uri, e);
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    private static String tryResolveExternalStoragePath(Context context, Uri uri) {
        if (!isExternalStorageDocument(uri)) {
            return null;
        }

        String docId = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri)) {
                docId = DocumentsContract.getDocumentId(uri);
            }
        } catch (Throwable ignored) {
            // ignore and fallback below
        }

        if (docId == null || docId.length() == 0) {
            String uriString = uri.toString();
            int markerIndex = uriString.indexOf("/document/");
            if (markerIndex >= 0 && markerIndex + 10 < uriString.length()) {
                docId = uriString.substring(markerIndex + 10);
            }
        }

        if (docId == null || docId.length() == 0) {
            String lastSegment = uri.getLastPathSegment();
            if (lastSegment == null || lastSegment.length() == 0) {
                return null;
            }
            docId = lastSegment;
        }

        try {
            docId = URLDecoder.decode(docId, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 always exists, keep raw string as fallback
        }

        if (docId.startsWith("document/")) {
            try {
                docId = docId.substring("document/".length());
            } catch (Throwable ignored) {
            }
        }

        String[] split = docId.split(":", 2);
        if (split.length < 2) {
            return null;
        }

        String type = split[0];
        String relativePath = split[1];
        if ("primary".equalsIgnoreCase(type)) {
            return Environment.getExternalStorageDirectory() + "/" + relativePath;
        }
        if ("home".equalsIgnoreCase(type)) {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/" + relativePath;
        }
        return null;
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

}
