<script setup>
defineProps({ name: String })
const paths = {
  notifications: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
  home: 'M3 3v18h18 M7 16v-5 M12 16V7 M17 16v-8',
  'decision-agent': 'M4 20V7h4v13 M10 20V4h4v16 M16 20v-8h4v8 M3 20h18 M18 2v6 M15 5h6',
  'org-improvements': 'M5 3h14v18H5z M8 8l2 2 4-4 M8 15h8 M8 18h5',
  monthly: 'M4 4h16v16H4z M4 9h16 M9 9v11 M14 9v11',
  annual: 'M4 5h16v16H4z M8 3v4 M16 3v4 M4 10h16 M8 14h3 M8 17h6',
  organizations: 'M8 21V3h9v18 M4 21h17 M11 7h3 M11 11h3 M11 15h3 M4 21V11h4',
  reports: 'M5 3h10l4 4v14H5z M14 3v5h5 M9 12h6 M9 16h6',
}
</script>
<template><svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.monthly" /></svg></template>
