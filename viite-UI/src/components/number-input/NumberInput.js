// Common input component for entering numbers
/* Usage:
import { numberInput } from '@/components/number-input/NumberInput.js';

const html = `
  <div class="form-group">
    <label for="roadNumber">Tienumero</label>
    ${numberInput('roadNumber', 4, false, '100')}
  </div>
`;
*/
export function numberInput(id, maxChars = null, isDisabled = false, value = '') {
    const disabledAttr = isDisabled ? 'readonly="readonly"' : '';
    const maxAttr = maxChars ? `maxlength="${maxChars}"` : '';
    
    return `
      <input type="text" 
        class="number-input"
        id="${id}" 
        value="${value}" 
        ${maxAttr} 
        ${disabledAttr}
        onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)">
    `.trim();
}
