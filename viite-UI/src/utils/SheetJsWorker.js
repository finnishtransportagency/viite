/* eslint-disable no-undef*/
// Listen for messages from the main thread
self.onmessage = function(event) {
    importScripts("../../../node_modules/xlsx/dist/xlsx.mini.min.js"); // import the SheetJS library
    const aoa = event.data[0]; // array of arrays
    const options = event.data[1];

    // convert the array of arrays to a sheet
    var worksheet = XLSX.utils.aoa_to_sheet(aoa, options);

    // Send the result back to the main thread
    self.postMessage(worksheet);
};
