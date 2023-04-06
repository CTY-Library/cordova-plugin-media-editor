//
//  CTYMediaEditor.js
//
//  Created by Josh Bavari on 01-14-2014
//  Modified by Ross Martin on 01-29-15
//

var exec = require('cordova/exec');
var pluginName = 'CTYMediaEditor';

function CTYMediaEditor() {}

CTYMediaEditor.prototype.transcodeVideo = function(success, error, options) {
  var self = this;
  var win = function(result) {
    if (typeof result.progress !== 'undefined') {
      if (typeof options.progress === 'function') {
        options.progress(result.progress);
      }
    } else {
      success(result);
    }
  };
  exec(win, error, pluginName, 'transcodeVideo', [options]);
};

CTYMediaEditor.prototype.transcodeAudio = function(success, error, options) {
  var self = this;
  var win = function(result) {
    if (typeof result.progress !== 'undefined') {
      if (typeof options.progress === 'function') {
        options.progress(result.progress);
      }
    } else {
      success(result);
    }
  };
  exec(win, error, pluginName, 'transcodeAudio', [options]);
};

CTYMediaEditor.prototype.trim = function(success, error, options) {
  var self = this;
  var win = function(result) {
    if (typeof result.progress !== 'undefined') {
      if (typeof options.progress === 'function') {
        options.progress(result.progress);
      }
    } else {
      success(result);
    }
  };
  exec(win, error, pluginName, 'trim', [options]);
};

CTYMediaEditor.prototype.createThumbnail = function(success, error, options) {
  exec(success, error, pluginName, 'createThumbnail', [options]);
};

CTYMediaEditor.prototype.getVideoInfo = function(success, error, options) {
  exec(success, error, pluginName, 'getVideoInfo', [options]);
};


module.exports = new CTYMediaEditor();
