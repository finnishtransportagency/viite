/* Usage: 
import { checkbox } from '@/components/checkbox/Checkbox.js';

<div>
  ${checkbox({
    id,
    label,
    checked,
    style: 'font-size: 13px'
  })}
</div>

*/

export function checkbox({ 
	id = '', 
	name = id, 
	value = '', 
	label = '', 
	checked = false,
	tooltip = '',
	style = ''
} = {}) {
	const checkedAttribute = checked ? 'checked' : '';
	const titleAttribute = tooltip ? ` title="${tooltip}"` : '';
	const styleAttribute = style ? ` style="${style}"` : '';

	return `
    <div class="checkbox">
      <label class="checkbox-label"${titleAttribute}>
        <input
          class="checkbox-input"
          type="checkbox"
          id="${id}"
          name="${name}"
          value="${value}"
          ${checkedAttribute}
        >
        <span class="checkbox-text"${styleAttribute}>${label}</span>
      </label>
    </div>
  `.trim();
}