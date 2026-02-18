// View contains the main HTML content for user management
(function (root) {
    root.UserManagement = root.UserManagement || {};
    
    // Local constants
    const DEFAULT_COORDINATES = {
        zoom: 3,
        east: 440220,
        north: 7175360
    };
    
    const { getRoleDropdownHtml, getElinvoimakeskusDropdownHtml } = root.UserManagement.Dropdowns;

    root.UserManagement.View = {
        getContent: function () {
            const defaultZoom = DEFAULT_COORDINATES.zoom;
            const defaultEast = DEFAULT_COORDINATES.east;
            const defaultNorth = DEFAULT_COORDINATES.north;
            const defaultRoles = ["viite"];
            const defaultElinvoimakeskus = [1,2,3,4,5,6,7,8,9,10];

            return `
                <div class="user-management-content-wrapper">
        
                  <!-- Existing users-->
                  <div class="user-table-section user-management-form">
                    <h2>Nykyiset käyttäjät</h2>
                    <table class="table user-table">
                      <thead>
                        <tr>
                          <th>Käyttäjätunnus</th>
                          <th>Roolit</th>
                          <th>Zoom</th>
                          <th class="centered">Koordinaatit</th>
                          <th>Sallitut Elinvoimakeskukset</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody id="userTableBody"></tbody>
                    </table>
        
                    <div class="form-actions">
                      <button id="updateUsersButton" class="btn btn-primary">Tallenna muutokset</button>
                    </div>
                  </div>

                                    <fieldset class="user-management-form">
                    <h2>Lisää uusi käyttäjä</h2>
        
                    <!-- Username and coordinates on same row -->
                    <div class="form-row horizontal-row">
                      <div class="form-group username-group">
                        <label class="user-management-label" for="newUserUsername">Käyttäjätunnus:</label>
                        <input type="text" id="newUserUsername" class="form-control" placeholder="esim. ab1234">
                      </div>
        
                      <!-- Coordinates and zoom-->
                      <div class="coordinates-group">
                        <label class="user-management-label">Oletuskoordinaatit</label>
                        <div class="coordinate-wrapper">
                          <div class="coordinate-input">
                            <label class="user-management-label" for="newUserNorth">P:</label>
                            <input type="number" id="newUserNorth" class="coord-input north form-control" value="${defaultNorth}">
                          </div>
                          <div class="coordinate-input">
                            <label class="user-management-label" for="newUserEast">I:</label>
                            <input type="number" id="newUserEast" class="coord-input east form-control" value="${defaultEast}">
                          </div>
                          <div class="coordinate-input">
                            <label class="user-management-label" for="newUserZoom">Zoom:</label>
                            <input type="number" id="newUserZoom" class="zoom-input form-control coord-input" min="1" max="10" value="${defaultZoom}">
                          </div>
                        </div>
                      </div>
                    </div>
        
                    <!-- Roles and Elinvoimakeskus -->
                    <div class="form-row horizontal-row">
                      <div class="form-group">
                        <label class="user-management-label">Roolit:</label>
                        ${getRoleDropdownHtml('newUserRoles', defaultRoles)}
                      </div>
                      <div class="form-group">
                        <label class="user-management-label">Sallitut Elinvoimakeskukset</label>
                        ${getElinvoimakeskusDropdownHtml('newUserElinvoimakeskus', defaultElinvoimakeskus)}
                      </div>
                    </div>
        
                    <div class="form-actions">
                      <button id="addUserButton" class="btn btn-primary">Lisää käyttäjä</button>
                    </div>
                  </fieldset>
                </div>
              `;
        }
    };
}(this));
