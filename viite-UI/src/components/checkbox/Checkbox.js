/* Usage: 
import { checkbox } from '@/components/checkbox/Checkbox.js';

<div>
  ${checkbox({
    id,
    label,
    checked
  })}
</div>

*/

export function checkbox({ 
  id = '', 
  name = id, 
  value = '', 
  label = '', 
  checked = false
} = {}) {
  const checkedAttribute = checked ? 'checked' : '';

  return `
    <div class="common-checkbox">
      <label class="common-checkbox-label">
        <input
          class="common-checkbox-input"
          type="checkbox"
          id="${id}"
          name="${name}"
          value="${value}"
          ${checkedAttribute}
        >
        <span class="common-checkbox-text">${label}</span>
      </label>
    </div>
  `.trim();
}