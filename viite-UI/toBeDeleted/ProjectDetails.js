(function (root) {
  root.ProjectFormValidator = function () {
    
    const formIsInvalid = function (rootElement) {
      const dateRegex = /^\d{1,2}.\d{1,2}.\d{4}$/;
      const startDateValue = rootElement.find('#projectStartDate').val();
      return !((rootElement.find('#nimi').val() && startDateValue !== '') && dateRegex.test(startDateValue));
    };

    const projDateEmpty = function (rootElement) {
      return !rootElement.find('#projectStartDate').val();
    };

    
    // Public API
    return {
      formIsInvalid,
      projDateEmpty,
      checkDateNotification: function(projectStartDate) {
        const validationUtils = new root.ValidationUtils();
        return validationUtils.checkDateNotification(projectStartDate);
      },
      isRoadPartInvalid: function(rootElement) {
        const validationUtils = new root.ValidationUtils();
        return validationUtils.isRoadPartInvalid(rootElement);
      }
    };
  };
}(this));
