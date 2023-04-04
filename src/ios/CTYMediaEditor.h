//
//  CTYMediaEditor.h
//
//  Created by Josh Bavari on 01-14-2014
//  Modified by Ross Martin on 01-29-2015
//  Modified by Kevin Tan on 03-30-2023
//

#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <AssetsLibrary/ALAssetsLibrary.h>
#import <MediaPlayer/MediaPlayer.h>

#import <Cordova/CDV.h>

enum CTYOutputFileType {
    M4V = 0,
    MPEG4 = 1,
    M4A = 2,
    QUICK_TIME = 3,
    MP3 = 4,
    WAV = 5
};
typedef NSUInteger CTYOutputFileType;

@interface CTYMediaEditor : CDVPlugin {
}

- (void)transcodeVideo:(CDVInvokedUrlCommand*)command;
- (void) createThumbnail:(CDVInvokedUrlCommand*)command;
- (void) getVideoInfo:(CDVInvokedUrlCommand*)command;
- (void) trimVideo:(CDVInvokedUrlCommand*)command;

- (void) sendCmd : (NSString *)msg keepCallback: (BOOL ) keepCallback;
@end
