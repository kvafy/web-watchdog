
// Externs file for the Google Closure compiler (goog).
// Purpose of this file is to tell goog which names are referencing to
// externally defined JavaScript symbols (eg. included in the HTML code)
// and therefore the names cannot be munged during advanced optimization.


// jQuery (only functions used in this project)

function jQuery(arg1, arg2) {}

jQuery.prototype.on = function(arg1, selector, data, handler) {};
jQuery.prototype.getJSON = function(url, data, success) {};

var $ = jQuery;



// Bootstrap Popover plug-in

jQuery.prototype.popover = function(options) {};
