// View contains the main HTML content for user management
(function (root) {
    root.UserManagement = root.UserManagement || {};
    const { DEFAULT_COORDINATES } = root.UserManagement.Constants;
    const { getRoleDropdownHtml, getElyDropdownHtml } = root.UserManagement.Dropdowns;

    root.UserManagement.View = {
        getContent: function () {
            const defaultZoom = DEFAULT_COORDINATES.zoom;
            const defaultEast = DEFAULT_COORDINATES.east;
            const defaultNorth = DEFAULT_COORDINATES.north;
            const defaultRoles = ["viite"];
            const defaultElys = [1];

            return `
                <div class="user-management-content-wrapper">
        
                  <fieldset class="user-management-form">
                    <h2>Lisää uusi käyttäjä</h2>
        
                    <!-- Username and coordinates on same row -->
                    <div class="form-row horizontal-row">
                      <div class="form-group username-group">
                        <label class="user-management-label" for="newUserUsername">Käyttäjätunnus:</label>
                        <input type="text" id="newUserUsername" class="form-control" placeholder="esim. ab1234">
                      </div>
        
                      <div class="coordinates-group">
                        <label class="user-management-label">Oletuskoordinaatit</label>
                        <div class="coordinate-wrapper">
                          <div class="coordinate-input">
                            <label class="user-management-label" for="newUserNorth">P:</label>
                            <input type="number" id="newUserNorth" class="coord-input form-control" value="${defaultNorth}">
                          </div>
                          <div class="coordinate-input">
                            <label class="user-management-label" for="newUserEast">I:</label>
                            <input type="number" id="newUserEast" class="coord-input form-control" value="${defaultEast}">
                          </div>
                          <div class="coordinate-input">
                            <label class="user-management-label" for="newUserZoom">Zoom:</label>
                            <input type="number" id="newUserZoom" class="zoom-input form-control coord-input" min="1" max="10" value="${defaultZoom}">
                          </div>
                        </div>
                      </div>
                    </div>
        
                    <!-- Roles and ELYs -->
                    <div class="form-row horizontal-row">
                      <div class="form-group">
                        <label class="user-management-label">Roolit:</label>
                        ${getRoleDropdownHtml('newUserRoles', defaultRoles)}
                      </div>
                      <div class="form-group">
                        <label class="user-management-label">Sallitut ELY:t</label>
                        ${getElyDropdownHtml('newUserElys', defaultElys)}
                      </div>
                    </div>
        
                    <div class="form-actions">
                      <button id="addUserButton" class="btn btn-primary">Lisää käyttäjä</button>
                    </div>
                  </fieldset>
        
                  <div class="user-table-section user-management-form">
                    <h2>Nykyiset käyttäjät</h2>
                    <table class="table user-table">
                      <thead>
                        <tr>
                          <th>Käyttäjätunnus</th>
                          <th>Roolit</th>
                          <th>Zoom</th>
                          <th class="centered">Koordinaatit</th>
                          <th>Sallitut ELY:t</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody id="userTableBody"></tbody>
                    </table>
        
                    <div class="form-actions">
                      <button id="updateUsersButton" class="btn btn-primary">Tallenna käyttäjä muutokset</button>
                    </div>
                  </div>
                </div>
              `;
        }
    };
})(this);
