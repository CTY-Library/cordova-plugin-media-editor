//
//  CTYMediaEditor.m
//
//  Created by Josh Bavari on 01-14-2014
//  Modified by Ross Martin on 01-29-2015
//  Modified by Kevin Tan on 03-30-2023
//

#import <Cordova/CDV.h>
#import "CTYMediaEditor.h"
#import "SDAVAssetExportSession.h"
#import "lame.h"


@interface CTYMediaEditor ()

@property (copy)  NSString *audioFileName;
@end

@implementation CTYMediaEditor

static NSString* myAsyncCallBackId = nil;
static CDVPluginResult *pluginResult = nil;

/**
 * transcodeVideo
 *
 * Transcodes a video
 *
 * ARGUMENTS
 * =========
 *
 * fileUri              - path to input video
 * outputFileName       - output file name
 * quality              - transcode quality
 * outputFileType       - output file type
 * saveToLibrary        - save to gallery
 * maintainAspectRatio  - make the output aspect ratio match the input video
 * width                - width for the output video
 * height               - height for the output video
 * videoBitrate         - video bitrate for the output video in bits
 * audioChannels        - number of audio channels for the output video
 * audioSampleRate      - sample rate for the audio (samples per second)
 * audioBitrate         - audio bitrate for the output video in bits
 *
 * RESPONSE
 * ========
 *
 * outputFilePath - path to output file
 *
 * @param CDVInvokedUrlCommand command
 * @return void
 */
- (void) transcodeVideo:(CDVInvokedUrlCommand*)command
{
    NSDictionary* options = [command.arguments objectAtIndex:0];

    if ([options isKindOfClass:[NSNull class]]) {
        options = [NSDictionary dictionary];
    }

    NSString *inputFilePath = [options objectForKey:@"fileUri"];
    NSURL *inputFileURL = [self getURLFromFilePath:inputFilePath];
    NSString *videoFileName = [options objectForKey:@"outputFileName"];
   // CTYOutputFileType outputFileType = ([options objectForKey:@"outputFileType"]) ? [[options objectForKey:@"outputFileType"] intValue] : MPEG4;
    BOOL optimizeForNetworkUse = ([options objectForKey:@"optimizeForNetworkUse"]) ? [[options objectForKey:@"optimizeForNetworkUse"] intValue] : NO;
    BOOL saveToPhotoAlbum = [options objectForKey:@"saveToLibrary"] ? [[options objectForKey:@"saveToLibrary"] boolValue] : YES;
    //float videoDuration = [[options objectForKey:@"duration"] floatValue];
    BOOL maintainAspectRatio = [options objectForKey:@"maintainAspectRatio"] ? [[options objectForKey:@"maintainAspectRatio"] boolValue] : YES;
    float width = [[options objectForKey:@"width"] floatValue];
    float height = [[options objectForKey:@"height"] floatValue];
    int videoBitrate = ([options objectForKey:@"videoBitrate"]) ? [[options objectForKey:@"videoBitrate"] intValue] : 1000000; // default to 1 megabit
    int audioChannels = ([options objectForKey:@"audioChannels"]) ? [[options objectForKey:@"audioChannels"] intValue] : 2;
    int audioSampleRate = ([options objectForKey:@"audioSampleRate"]) ? [[options objectForKey:@"audioSampleRate"] intValue] : 44100;
    int audioBitrate = ([options objectForKey:@"audioBitrate"]) ? [[options objectForKey:@"audioBitrate"] intValue] : 128000; // default to 128 kilobits

    NSString *strOutputFileType = [options objectForKey:@"outputFileType"] ;
    CTYOutputFileType outputFileType =  MPEG4;
    if ([strOutputFileType isEqual: @"M4V"]) {
        outputFileType = M4V;
    }
    else if ([strOutputFileType isEqual: @"M4A"]) {
        outputFileType = M4A;
    }
    else if ([strOutputFileType isEqual: @"QUICK_TIME"]) {
        outputFileType = QUICK_TIME;
    }
    else if ([strOutputFileType isEqual: @"MP3"]) {
        outputFileType = MP3;
    }
    else if ([strOutputFileType isEqual: @"WAV"]) {
        outputFileType = WAV;
    }


    NSString *stringOutputFileType = Nil;
    NSString *outputExtension = Nil;
  
    switch (outputFileType) {
        case QUICK_TIME:
            stringOutputFileType = AVFileTypeQuickTimeMovie;
            outputExtension = @".mov";
            break;
        case M4A:
            stringOutputFileType = AVFileTypeAppleM4A;
            outputExtension = @".m4a";
            break;
        case MP3:
            stringOutputFileType = AVFileTypeMPEGLayer3;
            outputExtension = @".mp3";
            break;
        case M4V:
            stringOutputFileType = AVFileTypeAppleM4V;
            outputExtension = @".m4v";
            break;
        case MPEG4:
        default:
            stringOutputFileType = AVFileTypeMPEG4;
            outputExtension = @".mp4";
            break;
    }

    // check if the video can be saved to photo album before going further
    if (saveToPhotoAlbum && !UIVideoAtPathIsCompatibleWithSavedPhotosAlbum([inputFileURL path]))
    {
        NSString *error = @"Video cannot be saved to photo album";
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error ] callbackId:command.callbackId];
        return;
    }

    AVURLAsset *avAsset = [AVURLAsset URLAssetWithURL:inputFileURL options:nil];

    NSString *cacheDir = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES) objectAtIndex:0];
    NSString *outputPath = [NSString stringWithFormat:@"%@/%@%@", cacheDir, videoFileName, outputExtension];
    NSURL *outputURL = [NSURL fileURLWithPath:outputPath];

    NSArray *tracks = [avAsset tracksWithMediaType:AVMediaTypeVideo];
    AVAssetTrack *track = [tracks objectAtIndex:0];
    CGSize mediaSize = track.naturalSize;

    float videoWidth = mediaSize.width;
    float videoHeight = mediaSize.height;
    int newWidth;
    int newHeight;

    if (maintainAspectRatio) {
        float aspectRatio = videoWidth / videoHeight;

        // for some portrait videos ios gives the wrong width and height, this fixes that
        NSString *videoOrientation = [self getOrientationForTrack:avAsset];
        if ([videoOrientation isEqual: @"portrait"]) {
            if (videoWidth > videoHeight) {
                videoWidth = mediaSize.height;
                videoHeight = mediaSize.width;
                aspectRatio = videoWidth / videoHeight;
            }
        }

        newWidth = (width && height) ? height * aspectRatio : videoWidth;
        newHeight = (width && height) ? newWidth / aspectRatio : videoHeight;
    } else {
        newWidth = (width && height) ? width : videoWidth;
        newHeight = (width && height) ? height : videoHeight;
    }

    NSLog(@"input videoWidth: %f", videoWidth);
    NSLog(@"input videoHeight: %f", videoHeight);
    NSLog(@"output newWidth: %d", newWidth);
    NSLog(@"output newHeight: %d", newHeight);

    SDAVAssetExportSession *encoder = [SDAVAssetExportSession.alloc initWithAsset:avAsset];
    encoder.outputFileType = stringOutputFileType;
    encoder.outputURL = outputURL;
    encoder.shouldOptimizeForNetworkUse = optimizeForNetworkUse;
    encoder.videoSettings = @
    {
        AVVideoCodecKey: AVVideoCodecH264,
        AVVideoWidthKey: [NSNumber numberWithInt: newWidth],
        AVVideoHeightKey: [NSNumber numberWithInt: newHeight],
        AVVideoCompressionPropertiesKey: @
        {
            AVVideoAverageBitRateKey: [NSNumber numberWithInt: videoBitrate],
            AVVideoProfileLevelKey: AVVideoProfileLevelH264High40
        }
    };
    encoder.audioSettings = @
    {
        AVFormatIDKey: @(kAudioFormatMPEG4AAC),
        AVNumberOfChannelsKey: [NSNumber numberWithInt: audioChannels],
        AVSampleRateKey: [NSNumber numberWithInt: audioSampleRate],
        AVEncoderBitRateKey: [NSNumber numberWithInt: audioBitrate]
    };

    /* // setting timeRange is not possible due to a bug with SDAVAssetExportSession (https://github.com/rs/SDAVAssetExportSession/issues/28)
     if (videoDuration) {
     int32_t preferredTimeScale = 600;
     CMTime startTime = CMTimeMakeWithSeconds(0, preferredTimeScale);
     CMTime stopTime = CMTimeMakeWithSeconds(videoDuration, preferredTimeScale);
     CMTimeRange exportTimeRange = CMTimeRangeFromTimeToTime(startTime, stopTime);
     encoder.timeRange = exportTimeRange;
     }
     */

    //  Set up a semaphore for the completion handler and progress timer
    dispatch_semaphore_t sessionWaitSemaphore = dispatch_semaphore_create(0);

    void (^completionHandler)(void) = ^(void)
    {
        dispatch_semaphore_signal(sessionWaitSemaphore);
    };

    // do it

    [self.commandDelegate runInBackground:^{
        [encoder exportAsynchronouslyWithCompletionHandler:completionHandler];

        do {
            dispatch_time_t dispatchTime = DISPATCH_TIME_FOREVER;  // if we dont want progress, we will wait until it finishes.
            dispatchTime = getDispatchTimeFromSeconds((float)1.0);
            double progress = [encoder progress] * 100;

            NSMutableDictionary *dictionary = [[NSMutableDictionary alloc] init];
            [dictionary setValue: [NSNumber numberWithDouble: progress] forKey: @"progress"];

            CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary: dictionary];

            [result setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
            
            dispatch_semaphore_wait(sessionWaitSemaphore, dispatchTime);
        } while( [encoder status] < AVAssetExportSessionStatusCompleted );

        // this is kinda odd but must be done
        if ([encoder status] == AVAssetExportSessionStatusCompleted) {
            NSMutableDictionary *dictionary = [[NSMutableDictionary alloc] init];
            // AVAssetExportSessionStatusCompleted will not always mean progress is 100 so hard code it below
            double progress = 100.00;
            [dictionary setValue: [NSNumber numberWithDouble: progress] forKey: @"progress"];

            CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary: dictionary];

            [result setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
        }

        if (encoder.status == AVAssetExportSessionStatusCompleted)
        {
            NSLog(@"Video export succeeded");
            if (saveToPhotoAlbum) {
                UISaveVideoAtPathToSavedPhotosAlbum(outputPath, self, nil, nil);
            }
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:outputPath] callbackId:command.callbackId];
        }
        else if (encoder.status == AVAssetExportSessionStatusCancelled)
        {
            NSLog(@"Video export cancelled");
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Video export cancelled"] callbackId:command.callbackId];
        }
        else
        {
            NSString *error = [NSString stringWithFormat:@"Video export failed with error: %@ (%ld)", encoder.error.localizedDescription, (long)encoder.error.code];
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error] callbackId:command.callbackId];
        }
    }];
}

/**
 * transcodeAudio
 *
 * Transcodes a audio
 *
 * ARGUMENTS
 * =========
 *
 * fileUri              - path to input audio
 * outputFileName       - output file name
 * outputFileType       - output file type
 * saveToLibrary        - save to gallery
 * audioChannels        - number of audio channels for the output video
 * audioSampleRate      - sample rate for the audio (samples per second)
 * audioBitrate         - audio bitrate for the output video in bits
 *
 * RESPONSE
 * ========
 *
 * outputFilePath - path to output file
 *
 * @param CDVInvokedUrlCommand command
 * @return void
 */
- (void) transcodeAudio:(CDVInvokedUrlCommand*)command
{

    NSDictionary* options = [command.arguments objectAtIndex:0];

    if ([options isKindOfClass:[NSNull class]]) {
        options = [NSDictionary dictionary];
    }
    
    
    NSString *inputFilePath = [options objectForKey:@"fileUri"];

    NSURL *inputFileURL = [self getURLFromFilePath:inputFilePath];
    _audioFileName = [options objectForKey:@"outputFileName"];
   // CTYOutputFileType outputFileType = ([options objectForKey:@"outputFileType"]) ? [[options objectForKey:@"outputFileType"] intValue] : WAV;
    BOOL optimizeForNetworkUse = ([options objectForKey:@"optimizeForNetworkUse"]) ? [[options objectForKey:@"optimizeForNetworkUse"] intValue] : NO;
    BOOL saveToPhotoAlbum = [options objectForKey:@"saveToLibrary"] ? [[options objectForKey:@"saveToLibrary"] boolValue] : YES;
    int audioChannels = ([options objectForKey:@"audioChannels"]) ? [[options objectForKey:@"audioChannels"] intValue] : 2;
    int audioSampleRate = ([options objectForKey:@"audioSampleRate"]) ? [[options objectForKey:@"audioSampleRate"] intValue] : 44100;
    int audioBitrate = ([options objectForKey:@"audioBitrate"]) ? [[options objectForKey:@"audioBitrate"] intValue] : 128000; // default to 128 kilobits


    NSString *strOutputFileType = [options objectForKey:@"outputFileType"] ;
    CTYOutputFileType outputFileType =  MP3;
    if ([strOutputFileType isEqual: @"M4A"]) {
        outputFileType = M4A;
    }
    else if ([strOutputFileType isEqual: @"MP3"]) {
        outputFileType = MP3;
    }
    else if ([strOutputFileType isEqual: @"WAV"]) {
        outputFileType = WAV;
    }

    NSString *stringOutputFileType = Nil;
    NSString *outputExtension = Nil;

    switch (outputFileType) {
        case M4A:
            stringOutputFileType = AVFileTypeAppleM4A;
            outputExtension = @".m4a";
            break;
        case MP3:
        default:
            stringOutputFileType = AVFileTypeMPEGLayer3;
            outputExtension = @".mp3";
            break;
        case WAV:
            stringOutputFileType = AVFileTypeWAVE;
            outputExtension = @".wav";
            break;
    }

    stringOutputFileType = AVFileTypeWAVE;
    outputExtension = @".wav";
    
    // check if the video can be saved to photo album before going further
    if (saveToPhotoAlbum && !UIVideoAtPathIsCompatibleWithSavedPhotosAlbum([inputFileURL path]))
    {
        NSString *error = @"Video cannot be saved to photo album";
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error ] callbackId:command.callbackId];
        return;
    }

    AVURLAsset *avAsset = [AVURLAsset URLAssetWithURL:inputFileURL options:nil];

    //NSString *cacheDir = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) objectAtIndex:0];//NSCachesDirectory
    NSString *cacheDir = NSTemporaryDirectory();
    NSString *outputPath = [NSString stringWithFormat:@"%@/%@%@", cacheDir, _audioFileName, outputExtension];
    NSURL *outputURL = [NSURL fileURLWithPath:outputPath];
    

    [self convertM4aToWav : inputFileURL  outPath:outputURL  outputFileType:outputFileType];
    
    myAsyncCallBackId = command.callbackId;
    [pluginResult setKeepCallbackAsBool:YES]; //不销毁，保存监听回调
    
}

//转换为mp3
- (NSString *)convertToMp3WithResult:(NSString *)originalPath outPath:(NSString *)outPath  {
    
    [[NSFileManager defaultManager] removeItemAtPath:outPath error:nil];
  
    @try {
        int read, write;

        if ([originalPath containsString:@"file:///"]) {
            originalPath = [originalPath substringFromIndex:7];
        }
        if ([outPath containsString:@"file:///"]) {
            outPath = [outPath substringFromIndex:7];
        }
        FILE *pcm = fopen([originalPath cStringUsingEncoding:1], "rb");//被转换的文件
        fseek(pcm, 4*1024, SEEK_CUR);                                   //skip file header
        FILE *mp3 = fopen([outPath cStringUsingEncoding:1], "wb");//转换后文件的存放位置
        
        const int PCM_SIZE = 8192;
        const int MP3_SIZE = 8192;
        short int pcm_buffer[PCM_SIZE*2];
        unsigned char mp3_buffer[MP3_SIZE];
        
        lame_t lame = lame_init();
        lame_set_num_channels (lame, 2 ); // 设置 1 为单通道，默认为 2 双通道
        lame_set_in_samplerate(lame, 44100);//
        lame_set_brate (lame, 8);
        lame_set_mode (lame, 3);
        lame_set_VBR(lame, vbr_default);
        lame_set_quality (lame, 2); /* 2=high  5 = medium  7=low 音 质 */
        lame_init_params(lame);
        
        do {
            read = fread(pcm_buffer, 2*sizeof(short int), PCM_SIZE, pcm);
            if (read == 0)
                write = lame_encode_flush(lame, mp3_buffer, MP3_SIZE);
            else
                write = lame_encode_buffer_interleaved(lame, pcm_buffer, read, mp3_buffer, MP3_SIZE);
            
            fwrite(mp3_buffer, write, 1, mp3);
            
        } while (read != 0);
        
        lame_close(lame);
        fclose(mp3);
        fclose(pcm);
    }
    @catch (NSException *exception) {
        // NSLog(@"%@",[exception description]);
    }
    @finally {
        [[NSFileManager defaultManager] removeItemAtPath:originalPath error:nil];
        [self sendCmd : outPath keepCallback:NO];
        return outPath;
    }
}

-  (void)  sendCmd : (NSString *)msg  keepCallback: (BOOL ) keepCallback
{
    if(myAsyncCallBackId != nil)
    {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString: msg ];
        //将 CDVPluginResult.keepCallback 设置为 true ,则不会销毁callback
        if(keepCallback){
         [pluginResult  setKeepCallbackAsBool:YES];
        }
        [self.commandDelegate  sendPluginResult:pluginResult callbackId: myAsyncCallBackId];

    }
}


- (NSString *)convertM4aToWav:(NSURL *)originalUrl outPath:(NSURL *)outPutUrl outputFileType:(CTYOutputFileType  )outputFileType {

    AVURLAsset *songAsset = [AVURLAsset URLAssetWithURL:originalUrl options:nil];    //读取原始文件信息
    NSError *error = nil;
    AVAssetReader *assetReader = [AVAssetReader assetReaderWithAsset:songAsset error:&error];
    if (error) {
        NSLog (@"error: %@", error);
        return @"";
    }
    AVAssetReaderOutput *assetReaderOutput = [AVAssetReaderAudioMixOutput assetReaderAudioMixOutputWithAudioTracks:songAsset.tracks  audioSettings: nil];
    if (![assetReader canAddOutput:assetReaderOutput]) {
        NSLog (@"can't add reader output... die!");
        return @"";
    }
    [assetReader addOutput:assetReaderOutput];
    
    AVAssetWriter *assetWriter = [AVAssetWriter assetWriterWithURL:outPutUrl                                                            fileType:AVFileTypeCoreAudioFormat error:&error];
    if (error) {
        NSLog (@"error: %@", error);
        return @"";
    }
    AudioChannelLayout channelLayout;
    memset(&channelLayout, 0, sizeof(AudioChannelLayout));
    channelLayout.mChannelLayoutTag = kAudioChannelLayoutTag_Stereo;
    
    /** 配置音频参数 */
    NSDictionary *outputSettings = [NSDictionary dictionaryWithObjectsAndKeys:
                                    [NSNumber numberWithInt:kAudioFormatLinearPCM],AVFormatIDKey,  //value , key
                                    [NSNumber numberWithFloat:44100.0], AVSampleRateKey,
                                    [NSNumber numberWithInt:2], AVNumberOfChannelsKey,
                                    [NSData dataWithBytes:&channelLayout length:sizeof(AudioChannelLayout)],AVChannelLayoutKey,
                                    [NSNumber numberWithInt:16],   AVLinearPCMBitDepthKey,//32
                                    [NSNumber numberWithBool:NO],  AVLinearPCMIsNonInterleaved,
                                    [NSNumber numberWithBool:NO],  AVLinearPCMIsFloatKey,
                                    [NSNumber numberWithBool:NO],  AVLinearPCMIsBigEndianKey,
                                    nil];
    AVAssetWriterInput *assetWriterInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeAudio  outputSettings:outputSettings];
    if ([assetWriter canAddInput:assetWriterInput]) {
        [assetWriter addInput:assetWriterInput];
    } else {
        NSLog (@"can't add asset writer input... die!");
        return @"";
    }
    assetWriterInput.expectsMediaDataInRealTime = NO;
    [assetWriter startWriting];
    [assetReader startReading];
    AVAssetTrack *soundTrack = [songAsset.tracks objectAtIndex:0];
    CMTime startTime = CMTimeMake (0, soundTrack.naturalTimeScale);
    [assetWriter startSessionAtSourceTime:startTime];
    __block UInt64 convertedByteCount = 0;
    dispatch_queue_t mediaInputQueue = dispatch_queue_create("mediaInputQueue", NULL);
    [assetWriterInput requestMediaDataWhenReadyOnQueue:mediaInputQueue  usingBlock: ^{
        while (assetWriterInput.readyForMoreMediaData) {
            CMSampleBufferRef nextBuffer = [assetReaderOutput copyNextSampleBuffer];
            if (nextBuffer) {
                // append buffer
                [assetWriterInput appendSampleBuffer: nextBuffer];
                convertedByteCount += CMSampleBufferGetTotalSampleSize (nextBuffer);
            } else {
                [assetWriterInput markAsFinished];
                [assetWriter finishWritingWithCompletionHandler:^{
                    
                    if(outputFileType == MP3){
                        NSString *cacheDir = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES) objectAtIndex:0];
                        NSString *outputPathMp3 = [NSString stringWithFormat:@"%@/%@%@%@", cacheDir, _audioFileName,@"_tmp", @".mp3"];
                        NSURL *outputURLMp3 = [NSURL fileURLWithPath:outputPathMp3];
                        [self convertToMp3WithResult :[outPutUrl absoluteString]   outPath: [outputURLMp3 absoluteString ]];                    
                    }else{
                           [self sendCmd : [outPutUrl absoluteString]  keepCallback:NO ];
                    }
                }];
                [assetReader cancelReading];
              
                NSDictionary *outputFileAttributes = [[NSFileManager defaultManager]  attributesOfItemAtPath:[outPutUrl path]   error:nil];
                NSLog (@"FlyElephant %lld",[outputFileAttributes fileSize]);
         
                break;
          
            }
        }
    }];
    
    return @"";
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
 * atTime         - location in the video to create the thumbnail (in seconds),
 * width          - width of the thumbnail (optional)
 * height         - height of the thumbnail (optional)
 * quality        - quality of the thumbnail (between 1 and 100)
 *
 * RESPONSE
 * ========
 *
 * outputFilePath - path to output file
 *
 * @param CDVInvokedUrlCommand command
 * @return void
 */
- (void) createThumbnail:(CDVInvokedUrlCommand*)command
{
    NSLog(@"createThumbnail");
    NSDictionary* options = [command.arguments objectAtIndex:0];

    if ([options isKindOfClass:[NSNull class]]) {
        options = [NSDictionary dictionary];
    }

    NSString* srcVideoPath = [options objectForKey:@"fileUri"];
    NSString* outputFileName = [options objectForKey:@"outputFileName"];
    float atTime = ([options objectForKey:@"atTime"]) ? [[options objectForKey:@"atTime"] floatValue] : 0;
    float width = [[options objectForKey:@"width"] floatValue];
    float height = [[options objectForKey:@"height"] floatValue];
    float quality = ([options objectForKey:@"quality"]) ? [[options objectForKey:@"quality"] floatValue] : 100;
    float thumbQuality = quality * 1.0 / 100;

    int32_t preferredTimeScale = 600;
    CMTime time = CMTimeMakeWithSeconds(atTime, preferredTimeScale);

    UIImage* thumbnail = [self generateThumbnailImage:srcVideoPath atTime:time];

    if (width && height) {
        NSLog(@"got width and height, resizing image");
        CGSize newSize = CGSizeMake(width, height);
        thumbnail = [self scaleImage:thumbnail toSize:newSize];
        NSLog(@"new size of thumbnail, width x height = %f x %f", thumbnail.size.width, thumbnail.size.height);
    }

    NSString *cacheDir = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES) objectAtIndex:0];
    NSString *outputFilePath = [cacheDir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.%@", outputFileName, @"jpg"]];

    // write out the thumbnail
    if ([UIImageJPEGRepresentation(thumbnail, thumbQuality) writeToFile:outputFilePath atomically:YES])
    {
        NSLog(@"path to your video thumbnail: %@", outputFilePath);
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:outputFilePath] callbackId:command.callbackId];
    }
    else
    {
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"failed to create thumbnail file"] callbackId:command.callbackId];
    }
}

/**
 * getVideoInfo
 *
 * Creates a thumbnail from the start of a video.
 *
 * ARGUMENTS
 * =========
 * fileUri       - input file path
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
 * @param CDVInvokedUrlCommand command
 * @return void
 */
- (void) getVideoInfo:(CDVInvokedUrlCommand*)command
{
    NSLog(@"getVideoInfo");
    NSDictionary* options = [command.arguments objectAtIndex:0];

    if ([options isKindOfClass:[NSNull class]]) {
        options = [NSDictionary dictionary];
    }

    NSString *filePath = [options objectForKey:@"fileUri"];
    NSURL *fileURL = [self getURLFromFilePath:filePath];

    unsigned long long size = [[NSFileManager defaultManager] attributesOfItemAtPath:[fileURL path] error:nil].fileSize;

    AVURLAsset *avAsset = [AVURLAsset URLAssetWithURL:fileURL options:nil];

    NSArray *tracks = [avAsset tracksWithMediaType:AVMediaTypeVideo];
    AVAssetTrack *track = [tracks objectAtIndex:0];
    CGSize mediaSize = track.naturalSize;

    float videoWidth = mediaSize.width;
    float videoHeight = mediaSize.height;
    float aspectRatio = videoWidth / videoHeight;

    // for some portrait videos ios gives the wrong width and height, this fixes that
    NSString *videoOrientation = [self getOrientationForTrack:avAsset];
    if ([videoOrientation isEqual: @"portrait"]) {
        if (videoWidth > videoHeight) {
            videoWidth = mediaSize.height;
            videoHeight = mediaSize.width;
            aspectRatio = videoWidth / videoHeight;
        }
    }

    NSMutableDictionary *dict = [[NSMutableDictionary alloc] init];
    [dict setObject:[NSNumber numberWithFloat:videoWidth] forKey:@"width"];
    [dict setObject:[NSNumber numberWithFloat:videoHeight] forKey:@"height"];
    [dict setValue:videoOrientation forKey:@"orientation"];
    [dict setValue:[NSNumber numberWithFloat:track.timeRange.duration.value / 600.0] forKey:@"duration"];
    [dict setObject:[NSNumber numberWithLongLong:size] forKey:@"size"];
    [dict setObject:[NSNumber numberWithFloat:track.estimatedDataRate] forKey:@"bitrate"];

    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:dict] callbackId:command.callbackId];
}

/**
 * trim
 *
 * Performs a trim operation on a clip, while encoding it.
 *
 * ARGUMENTS
 * =========
 * fileUri        - input file path
 * trimStart      - time to start trimming
 * trimEnd        - time to end trimming
 * outputFileName - output file name
 * progress:      - optional callback function that receives progress info
 *
 * RESPONSE
 * ========
 *
 * outputFilePath - path to output file
 *
 * @param CDVInvokedUrlCommand command
 * @return void
 */
- (void) trimVideo:(CDVInvokedUrlCommand*)command {
    NSLog(@"[Trim]: trim called");

    // extract arguments
    NSDictionary* options = [command.arguments objectAtIndex:0];
    if ([options isKindOfClass:[NSNull class]]) {
        options = [NSDictionary dictionary];
    }
    NSString *inputFilePath = [options objectForKey:@"fileUri"];
    NSURL *inputFileURL = [self getURLFromFilePath:inputFilePath];
    float trimStart = [[options objectForKey:@"trimStart"] floatValue];
    float trimEnd = [[options objectForKey:@"trimEnd"] floatValue];
    NSString *outputName = [options objectForKey:@"outputFileName"];

    NSFileManager *fileMgr = [NSFileManager defaultManager];
    NSString *cacheDir = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES) objectAtIndex:0];

    // videoDir
    NSString *videoDir = [cacheDir stringByAppendingPathComponent:@"mp4"];
    if ([fileMgr createDirectoryAtPath:videoDir withIntermediateDirectories:YES attributes:nil error: NULL] == NO){
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"failed to create video dir"] callbackId:command.callbackId];
        return;
    }
    NSString *videoOutput = [videoDir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.%@", outputName, @"mp4"]];

    NSLog(@"[Trim]: inputFilePath: %@", inputFilePath);
    NSLog(@"[Trim]: outputPath: %@", videoOutput);

    // run in background
    [self.commandDelegate runInBackground:^{

        AVURLAsset *avAsset = [AVURLAsset URLAssetWithURL:inputFileURL options:nil];

        AVAssetExportSession *exportSession = [[AVAssetExportSession alloc]initWithAsset:avAsset presetName: AVAssetExportPresetHighestQuality];
        exportSession.outputURL = [NSURL fileURLWithPath:videoOutput];
        exportSession.outputFileType = AVFileTypeQuickTimeMovie;
        exportSession.shouldOptimizeForNetworkUse = YES;

        int32_t preferredTimeScale = 600;
        CMTime startTime = CMTimeMakeWithSeconds(trimStart, preferredTimeScale);
        CMTime stopTime = CMTimeMakeWithSeconds(trimEnd, preferredTimeScale);
        CMTimeRange exportTimeRange = CMTimeRangeFromTimeToTime(startTime, stopTime);
        exportSession.timeRange = exportTimeRange;

        // debug timings
        NSString *trimStart = (NSString *) CFBridgingRelease(CMTimeCopyDescription(NULL, startTime));
        NSString *trimEnd = (NSString *) CFBridgingRelease(CMTimeCopyDescription(NULL, stopTime));
        NSLog(@"[Trim]: duration: %lld, trimStart: %@, trimEnd: %@", avAsset.duration.value, trimStart, trimEnd);

        //  Set up a semaphore for the completion handler and progress timer
        dispatch_semaphore_t sessionWaitSemaphore = dispatch_semaphore_create(0);

        void (^completionHandler)(void) = ^(void)
        {
            dispatch_semaphore_signal(sessionWaitSemaphore);
        };

        // do it
        [exportSession exportAsynchronouslyWithCompletionHandler:completionHandler];

        do {
            dispatch_time_t dispatchTime = DISPATCH_TIME_FOREVER;  // if we dont want progress, we will wait until it finishes.
            dispatchTime = getDispatchTimeFromSeconds((float)1.0);
            double progress = [exportSession progress] * 100;

            NSLog([NSString stringWithFormat:@"AVAssetExport running progress=%3.2f%%", progress]);

            NSMutableDictionary *dictionary = [[NSMutableDictionary alloc] init];
            [dictionary setValue: [NSNumber numberWithDouble: progress] forKey: @"progress"]; // 返回到不同的js回调函数

            CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary: dictionary];

            [result setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
            dispatch_semaphore_wait(sessionWaitSemaphore, dispatchTime);
        } while( [exportSession status] < AVAssetExportSessionStatusCompleted );

        // this is kinda odd but must be done
        if ([exportSession status] == AVAssetExportSessionStatusCompleted) {
            NSMutableDictionary *dictionary = [[NSMutableDictionary alloc] init];
            // AVAssetExportSessionStatusCompleted will not always mean progress is 100 so hard code it below
            double progress = 100.00;
            [dictionary setValue: [NSNumber numberWithDouble: progress] forKey: @"progress"];

            CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary: dictionary];

            [result setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
        }

        switch ([exportSession status]) {
            case AVAssetExportSessionStatusCompleted:
                NSLog(@"[Trim]: Export Complete %d %@", exportSession.status, exportSession.error);
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:videoOutput] callbackId:command.callbackId];
                break;
            case AVAssetExportSessionStatusFailed:
                NSLog(@"[Trim]: Export failed: %@", [[exportSession error] localizedDescription]);
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[[exportSession error] localizedDescription]] callbackId:command.callbackId];
                break;
            case AVAssetExportSessionStatusCancelled:
                NSLog(@"[Trim]: Export canceled");
                break;
            default:
                NSLog(@"[Trim]: Export default in switch");
                break;
        }

    }];
}

// modified version of http://stackoverflow.com/a/21230645/1673842
- (UIImage *)generateThumbnailImage: (NSString *)srcVideoPath atTime:(CMTime)time
{
    NSURL *url = [NSURL fileURLWithPath:srcVideoPath];

    if ([srcVideoPath rangeOfString:@"://"].location == NSNotFound)
    {
        url = [NSURL URLWithString:[[@"file://localhost" stringByAppendingString:srcVideoPath] stringByAddingPercentEscapesUsingEncoding:NSUTF8StringEncoding]];
    }
    else
    {
        url = [NSURL URLWithString:[srcVideoPath stringByAddingPercentEscapesUsingEncoding: NSUTF8StringEncoding]];
    }

    AVAsset *asset = [AVAsset assetWithURL:url];
    AVAssetImageGenerator *imageGenerator = [[AVAssetImageGenerator alloc] initWithAsset:asset];
    imageGenerator.requestedTimeToleranceAfter = kCMTimeZero; // needed to get a precise time (http://stackoverflow.com/questions/5825990/i-cannot-get-a-precise-cmtime-for-generating-still-image-from-1-8-second-video)
    imageGenerator.requestedTimeToleranceBefore = kCMTimeZero; // ^^
    imageGenerator.appliesPreferredTrackTransform = YES; // crucial to have the right orientation for the image (http://stackoverflow.com/questions/9145968/getting-video-snapshot-for-thumbnail)
    CGImageRef imageRef = [imageGenerator copyCGImageAtTime:time actualTime:NULL error:NULL];
    UIImage *thumbnail = [UIImage imageWithCGImage:imageRef];
    CGImageRelease(imageRef);  // CGImageRef won't be released by ARC

    return thumbnail;
}

// to scale images without changing aspect ratio (http://stackoverflow.com/a/8224161/1673842)
- (UIImage*)scaleImage:(UIImage*)image
                toSize:(CGSize)newSize;
{
    float oldWidth = image.size.width;
    float scaleFactor = newSize.width / oldWidth;

    float newHeight = image.size.height * scaleFactor;
    float newWidth = oldWidth * scaleFactor;

    UIGraphicsBeginImageContext(CGSizeMake(newWidth, newHeight));
    [image drawInRect:CGRectMake(0, 0, newWidth, newHeight)];
    UIImage *newImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return newImage;
}

// inspired by http://stackoverflow.com/a/6046421/1673842
- (NSString*)getOrientationForTrack:(AVAsset *)asset
{
    AVAssetTrack *videoTrack = [[asset tracksWithMediaType:AVMediaTypeVideo] objectAtIndex:0];
    CGSize size = [videoTrack naturalSize];
    CGAffineTransform txf = [videoTrack preferredTransform];

    if (size.width == txf.tx && size.height == txf.ty)
        return @"landscape";
    else if (txf.tx == 0 && txf.ty == 0)
        return @"landscape";
    else if (txf.tx == 0 && txf.ty == size.width)
        return @"portrait";
    else
        return @"portrait";
}

- (NSURL*)getURLFromFilePath:(NSString*)filePath
{
    if([filePath containsString:@"%"] && ([filePath containsString:@"assets-library://"] || [filePath containsString:@"file://"]) ) {
        return  [NSURL URLWithString:filePath ];
    }
    else if([filePath containsString:@"%"]  ) {
        return  [NSURL fileURLWithPath:filePath ];
    }
    
    if ([filePath containsString:@"assets-library://"]) {
        return [NSURL URLWithString:[filePath stringByAddingPercentEscapesUsingEncoding:NSUTF8StringEncoding]];
    } else if ([filePath containsString:@"file://"]) {
        return [NSURL URLWithString:[filePath stringByAddingPercentEscapesUsingEncoding:NSUTF8StringEncoding]];
    }

    return [NSURL fileURLWithPath:[filePath stringByAddingPercentEscapesUsingEncoding:NSUTF8StringEncoding]];
}

static dispatch_time_t getDispatchTimeFromSeconds(float seconds) {
    long long milliseconds = seconds * 1000.0;
    dispatch_time_t waitTime = dispatch_time( DISPATCH_TIME_NOW, 1000000LL * milliseconds );
    return waitTime;
}

@end
