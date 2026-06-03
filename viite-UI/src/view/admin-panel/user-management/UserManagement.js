/**
 * UserManagagement - Contains rendering and initialization for user management
 */
import { AddUserForm } from './AddUserForm.js';
import { UpdateUserForm } from './UpdateUsersForm.js';
import { getRoleDropdownHtml, getElinvoimakeskusDropdownHtml } from './Dropdowns.js';

export const UserManagagement = {
    getContent: function () {
        const defaultRoles = ["viite"];
        const defaultElinvoimakeskus = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

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
                      <th>Sallitut Elinvoimakeskukset</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody id="userTableBody"></tbody>
                </table>
                <div class="form-actions">
                  <button id="toggleViiteAllButton" class="btn btn-secondary">Anna viite-oikeus kaikille</button>
                  <button id="updateUsersButton" class="btn btn-primary">Tallenna muutokset</button>
                </div>
              </div>

              <fieldset class="user-management-form">
                <h2>Lisää uusi käyttäjä</h2>
                <div class="form-row horizontal-row">
                  <div class="form-group username-group">
                    <label class="user-management-label" for="newUserUsername">Käyttäjätunnus:</label>
                    <input type="text" id="newUserUsername" class="form-control" placeholder="LX12345">
                  </div>
                </div>
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
    },

    init: function (containerSelector) {
        const container = document.querySelector(containerSelector);

        if (!container || !AddUserForm || !UpdateUserForm) {
            return;
        }

        container.innerHTML = UserManagagement.getContent();
        AddUserForm.bindEvents(containerSelector, {
            onUserAdded: function () {
                UpdateUserForm.fetchUsers({});
            }
        });
        UpdateUserForm.bindEvents(containerSelector, {});
        UpdateUserForm.fetchUsers({});
    }
};
