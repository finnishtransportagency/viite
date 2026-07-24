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
    <div class="checkbox">
      <label class="checkbox-label">
        <input
          class="checkbox-input"
          type="checkbox"
          id="${id}"
          name="${name}"
          value="${value}"
          ${checkedAttribute}
        >
        <span class="checkbox-text">${label}</span>
      </label>
    </div>
  `.trim();
}