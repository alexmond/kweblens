/**
 * Native attributes that fall through to a component's root element.
 *
 * `strictTemplates` (tsconfig.app.json) is what makes vue-tsc reject an unresolved component
 * tag, an undeclared prop, an undeclared event and an unknown directive — the things it was
 * blind to before, and the reason a `<ErrorNotice>` used without its import survived 62 green
 * CI runs. The price is that vue-tsc then also rejects any attribute a component does not
 * declare as a prop, including the plain HTML ones. Those are NOT mistakes: Vue's fallthrough
 * puts them on the component's root element (naive-ui's NButton renders `<button>` and inherits
 * attrs normally; NDrawer sets `inheritAttrs: false` but re-spreads `$attrs` onto its own
 * `role="dialog"` div), so `title` and every `aria-*` reach the DOM and do their job.
 *
 * Declaring them here keeps them typed and reaching the DOM rather than deleting an
 * accessibility attribute or hiding it behind a `v-bind` object the checker cannot see. It is
 * deliberately limited to attributes that are valid on EVERY element — a component-specific
 * prop typo is still an error, which is the whole point of turning strictTemplates on.
 */
import type { AriaAttributes } from 'vue';

declare module 'vue' {
  interface ComponentCustomProps extends AriaAttributes {
    title?: string;
  }
}

export {};
