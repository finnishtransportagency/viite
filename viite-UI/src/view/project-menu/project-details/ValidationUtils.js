(function (root) {
  root.ValidationUtils = function () {
    
    const checkDateNotification = function (projectStartDate) {
      let projectNotificationText = "";

      if (!projectStartDate || projectStartDate.length === 0) {
        return "";
      }

      const parts_DMY = projectStartDate.split('.');
      // Allowed characters for date input field
      const allowedChars = /^[0-9.]+$/;

      // Check the project start date input field for incorrect characters
      if (allowedChars.test(projectStartDate)) {
        projectNotificationText = "";
      }
      else {
        projectNotificationText = 'Päivämäärä saa sisältää vain numeroita tai pisteitä';
      }

      const day = parseInt(parts_DMY[0]);
      const month = parseInt(parts_DMY[1]);
      const year = parseInt(parts_DMY[2]);

      if (isNaN(day) || isNaN(month) || isNaN(year) || month < 1 || month > 12 || day < 1 || day > 31) {
        return 'Virheellinen päivämäärä. Tarkista päivä, kuukausi ja vuosi';
      }

      // Check the date input field for dates older than 20 years or dates over 1 year in the future
      const projectSD = new Date(year, month - 1, day);
      const nowDate = new Date();
      if (projectSD.getFullYear() < nowDate.getFullYear() - 20) {
        projectNotificationText = 'Vanha päiväys. Projektin alkupäivämäärä yli 20 vuotta historiassa. Varmista päivämäärän oikeellisuus ennen jatkamista.';
      }
      else if (projectSD.getFullYear() > nowDate.getFullYear() + 1) {
        projectNotificationText = 'Tulevaisuuden päiväys. Projektin alkupäivä yli vuoden verran tulevaisuudessa. Varmista päivämäärän oikeellisuus ennen jatkamista.';
      }

      return projectNotificationText;
    };

    const isRoadPartInvalid = function (_rootElement) {
      const values = [
        $('#tie').val(),
        $('#aosa').val(),
        $('#losa').val()
      ];

      const allNumbers = values.every(val => val !== "" && $.isNumeric(val));
      return !allNumbers;
    };

    // Public API
    return {
      checkDateNotification,
      isRoadPartInvalid
    };
  };
}(this));