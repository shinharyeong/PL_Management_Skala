import { reactive } from 'vue'
import initial from './ui-copy.json' with { type: 'json' }

// Keep the shared object stable when Vite reloads the JSON, so editing does not reset login or drafts.
const copy = reactive(initial)
export default copy
if (import.meta.hot) {
  import.meta.hot.accept('./ui-copy.json', module => {
    if (!module) return
    for (const [group, values] of Object.entries(module.default)) {
      if (!copy[group]) copy[group] = {}
      Object.assign(copy[group], values)
    }
  })
}
