
// Externs file for the Google Closure compiler (goog).
// Purpose of this file is to tell goog which names in clojurescript
// are referencing to externally defined JavaScript symbols (eg. JavaScript
// files included in the HTML code, not via ClojureScript) and therefore
// the names cannot be munged during advanced optimization during compilation
// of ClojureScript.


// jQuery (only functions used in this project)

function jQuery(arg1, arg2) {}

jQuery.prototype.on = function(arg1, selector, data, handler) {};
jQuery.prototype.getJSON = function(url, data, success) {};

var $ = jQuery;



// Bootstrap Popover plug-in

jQuery.prototype.popover = function(options) {};
