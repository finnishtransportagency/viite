/**
 * button - Self-managing button component
 *
 * Returns an HTML string and registers its own click listener. The listener is
 * cleaned up automatically via a shared MutationObserver when the element is
 * removed from the DOM — no manual bind() or destroy() needed.
 *
 * Pass a stable `id` when you need to update the button state externally:
 *   $('#my-btn').prop('disabled', true);
 *
 * Usage:
 *   import { button } from '@components/button/Button.js';
 *
 *   // Inline in a template literal:
 *   ${button({ label: 'Save', onClick: handleSave })}
 *
 *   // With a stable id for external disable toggling:
 *   ${button({ id: 'save-btn', label: 'Save', onClick: handleSave, disabled: true })}
 *   $('#save-btn').prop('disabled', false);
 *
 *   // Self-managing disabled state — re-evaluated on every change to watchSelector elements:
 *   ${button({
 *     id: 'search-btn',
 *     label: 'Search',
 *     onClick: handleSearch,
 *     disabled: true,
 *     disabledWhen: () => !$('#query').val(),
 *     watchSelector: '#query'
 *   })}
 */

const _registry = new Map(); // id -> cleanup fn
let _observer = null;

function _ensureObserver() {
	if (_observer) return;
	_observer = new MutationObserver(() => {
		for (const [id, cleanup] of _registry) {
			if (!document.getElementById(id)) {
				cleanup();
				_registry.delete(id);
			}
		}
	});
	_observer.observe(document.body, { childList: true, subtree: true });
}

export function button({
	id = `viite-btn-${Math.random().toString(36).slice(2, 9)}`,
	label = '',
	onClick = () => {},
	className = 'btn-primary',
	type = 'button',
	disabled = false,
	title = '',
	disabledWhen = null,  // () => boolean — re-evaluated on changes to watchSelector
	watchSelector = null  // CSS selector for elements that trigger re-evaluation
} = {}) {
	_ensureObserver();

	// If the same id is reused (re-render), remove the previous listeners first.
	if (_registry.has(id)) {
		_registry.get(id)();
		_registry.delete(id);
	}

	$(document).on(`click.${id}`, `#${id}:not([disabled])`, function (event) {
		onClick(event);
		// Prevent the button from remaining stuck in its :focus (darkened) style after click.
		event.currentTarget.blur();
	});

	if (disabledWhen && watchSelector) {
		$(document).on(`keyup.${id} input.${id}`, watchSelector, () => {
			$(`#${id}`).prop('disabled', disabledWhen());
		});
	}

	// .off(`.${id}`) removes ALL events under this namespace (click + optional keyup/input).
	_registry.set(id, () => $(document).off(`.${id}`));

	return `<button id="${id}" type="${type}" class="${className}"${disabled ? ' disabled' : ''}${title ? ` title="${title}"` : ''}>${label}</button>`;
}
