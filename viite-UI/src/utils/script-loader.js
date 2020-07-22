// eslint-disable-next-line no-unused-vars
var getScripts = function getScripts(urls, callback) {
  if (_.isEmpty(urls)) {
    return callback();
  } else {
    $.getScript(_.head(urls), function() {
      getScripts(_.rest(urls), callback);
    });
    return null;
  }
};
