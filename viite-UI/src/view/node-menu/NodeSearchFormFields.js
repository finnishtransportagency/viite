/**
 * NodeSearchFormFields provides lightweight input renderers used by the node search menu.
 */
export function createNodeSearchFormFields(prefix) {
  const inputClass = 'form-control node-input';

  const nodeInputNumber = function (id, maxLength) {
    return `<input type="number" class="${inputClass}" id="${id}" maxlength="${maxLength}" data-prefix="${prefix}">`;
  };

  return {
    nodeInputNumber: nodeInputNumber
  };
}
