$(document).ajaxError(function (event, jqXHR) {
  if (jqXHR.status === 401) {
    window.location = "index.html";
  } else if (jqXHR.status === 403) {
    window.location = "autherror.html";
  }
});
