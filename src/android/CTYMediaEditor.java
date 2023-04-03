package org.apache.cordova.CTYMediaEditor;

import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import android.graphics.Bitmap;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentUris;
import android.content.Context;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.linkedin.android.litr.MediaTransformer;
import com.linkedin.android.litr.TransformationListener;
import com.linkedin.android.litr.TransformationOptions;
import com.linkedin.android.litr.analytics.TrackTransformationInfo;


/**
 * CTYMediaEditor plugin for Android
 * Created by Ross Martin 2-2-15
 */
public class CTYMediaEditor extends CordovaPlugin {

    private static final String TAG = "CTYMediaEditor";

    private CallbackContext callback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute method starting");

        this.callback = callbackContext;

        if (action.equals("transcodeVideo")) {
            try {
                this.transcodeVideo(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        } if (action.equals("transcodeAudio")) {
            try {
                this.transcodeAudio(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("createThumbnail")) {
            try {
                this.createThumbnail(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("getVideoInfo")) {
            try {
                this.getVideoInfo(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        }

        return false;
    }

    private void transcodeAudioToWav(File inFile, String outputFilePath, boolean deleteInputFile, long startMillSeconds=0, long endMillSeconds=0)  throws JSONException, IOException {
        // val transformationListener = MediaTransformationListener(
        //     context,
        //     transformationState.requestId,
        //     transformationState,
        //     targetMedia
        // )

        TransformationListener listener = getTransformationListener(true, () -> {
            if(deleteInputFile){
                // 删除原文件
                inFile.delete();
                Log.d(TAG, "delete inFile:"+inFile.getAbsolutePath());
            }
            callback.success(outputFilePath);
        });

        MediaRange mediaRange = new MediaRange(
            TimeUnit.MILLISECONDS.toMicros((Math.max(startMillSeconds, 0) * 1000).toLong()),
            TimeUnit.MILLISECONDS.toMicros(((endMillSeconds>0?endMillSeconds:Long.MAX_VALUE) * 1000).toLong()));
        

        try {
            MimeType targetMimeType = MimeType.AUDIO_RAW; // else MimeType.AUDIO_AAC
            WavMediaTarget mediaTarget = new WavMediaTarget(outputFilePath);
                
                // MediaMuxerMediaTarget(
                //     outputFilePath,
                //     1,
                //     0,
                //     MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                // )

            MediaExtractorMediaSource mediaSource = new MediaExtractorMediaSource(context, inFile.getAbsolutePath(), mediaRange);

            List<TrackTransform> trackTransforms = new List<TrackTransform>();
            for (targetTrack in targetMedia.tracks) {
                if (targetTrack.format is AudioTrackFormat) {
                    AudioTrackFormat trackFormat = targetTrack.format as AudioTrackFormat
                    MediaFormat mediaFormat = MediaFormat.createAudioFormat(
                        targetMimeType,
                        trackFormat.samplingRate,
                        trackFormat.channelCount
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, trackFormat.bitrate)
                        setLong(MediaFormat.KEY_DURATION, trackFormat.duration)
                    }

                    PassthroughBufferEncoder encoder = new PassthroughBufferEncoder(8192); // else MediaCodecEncoder()

                    TrackTransform trackTransform = TrackTransform.Builder(
                        mediaSource,
                        targetTrack.sourceTrackIndex,
                        mediaTarget
                    )
                        .setTargetTrack(0)
                        .setDecoder(MediaCodecDecoder())
                        .setEncoder(encoder)
                        .setRenderer(AudioRenderer(encoder))
                        .setTargetFormat(mediaFormat)
                        .build();
                    trackTransforms.add(trackTransform);
                    break;
                }
            }
            MediaTransformer mediaTransformer = new MediaTransformer(cordova.getContext());
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
        File inFile = this.resolveLocalFileSystemURI(options.getString("fileUri"));

        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input audio does not exist.");
            return;
        } 

       outputFilePath = getOutputFile(options, "audio", ".mp3");
       if(outputFilePath.equals("")){
            return;
       }

       TransformationListener listener = getTransformationListener(true, () -> {
            if(deleteInputFile){
                // 删除原文件
                inFile.delete();
                Log.d(TAG, "delete inFile:"+inFile.getAbsolutePath());
            }
            callback.success(outputFilePath);
        });

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
            try {
                  // 指定编码器颜色格式
                  MediaFormat targetVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 0, 0);
  
                  MediaFormat targetAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate, channelCount);
                  targetAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
  
                  MediaTransformer mediaTransformer = new MediaTransformer(cordova.getContext());
                  TransformationOptions opt = null;// new TransformationOptions(1,null,null,null,false,false); //todo 参数设置 ?
                  mediaTransformer.transform(UUID.randomUUID().toString(), Uri.fromFile(inFile),outputFilePath,targetVideoFormat,targetAudioFormat,listener,opt);
                  } catch (Throwable e) {
                      Log.d(TAG, "transcode exception ", e);
                      callback.error(e.toString());
                  }
              }
        });
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

        TransformationListener listener = getTransformationListener(true, () -> {
            if(deleteInputFile){
                // 删除原文件
                inFile.delete();
                Log.d(TAG, "delete inFile:"+inFile.getAbsolutePath());
            }
            callback.success(outputFilePath);
        });

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
            try {
                  // 指定编码器颜色格式
                  MediaFormat targetVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
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

        if (saveToLibrary) {
            mediaStorageDir = new File(Environment.getExternalStorageDirectory() + (mediaType.equals("video")?"/Movies":"/Music"), appName);
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

        outputFilePath = new File(mediaStorageDir.getPath(), outputFileName + outputExtension).getAbsolutePath();
        Log.d(TAG, "outputFilePath: " + outputFilePath);       
        return outputFilePath;
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
                    PluginResult progressResult = new PluginResult(PluginResult.Status.OK, progress); //Float.toString(progress)
                    progressResult.setKeepCallback(true);
                    callback.sendPluginResult(progressResult);
                }
            }
    
            @Override
            public void onCompleted(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
                mediaTransformer.release();
                successCallback.run();
                // if(deleteInputFile){
                //     // 删除原文件
                //     inFile.delete();
                //     Log.d(TAG, "delete inFile:"+inFile.getAbsolutePath());
                // }
                // callback.success(outputFilePath);
                Log.d(TAG, "TransformationListener onCompleted");
            }
    
            @Override
            public void onCancelled(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
    
            }
    
            @Override
            public void onError(@NonNull String id, @Nullable Throwable cause, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
                mediaTransformer.release();
                //PluginResult progressResult = new PluginResult(PluginResult.Status.OK, "error");
                //progressResult.setKeepCallback(true);
                //callback.sendPluginResult(progressResult);
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
     * @param JSONArray args
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
     * @param JSONArray args
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
        if (decoded.startsWith("content:")) {
            fp = new File(getPath(this.cordova.getActivity().getApplicationContext(), Uri.parse(decoded)));
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
            throw new FileNotFoundException( "" + url + " -> " + fp.getCanonicalPath());
        }
        if (!fp.canRead()) {
            throw new IOException("can't read file: " + url + " -> " + fp.getCanonicalPath());
        }
        return fp;
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

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
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
        } finally {
            if (cursor != null)
                cursor.close();
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
