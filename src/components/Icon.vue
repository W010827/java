<script setup>
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 }
})

const paths = {
  dashboard: ['M4 4h6v7H4z', 'M14 4h6v4h-6z', 'M14 12h6v8h-6z', 'M4 15h6v5H4z'],
  software: ['M12 3l8 4.5v9L12 21l-8-4.5v-9z', 'M4 7.5l8 4.5 8-4.5', 'M12 12v9'],
  report: [
    'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z',
    'M14 3v5h5',
    'M9 13h6',
    'M9 17h4'
  ],
  log: ['M4 20h4L18 10a2.8 2.8 0 1 0-4-4L4 16z', 'M13.5 6.5l4 4'],
  todo: ['M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z', 'M9 12.5l2.2 2.2L15.5 10'],
  document: ['M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'],
  plus: ['M12 5v14', 'M5 12h14'],
  search: ['M11 4.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13z', 'M20 20l-3.8-3.8'],
  download: ['M12 4v10', 'M8 11l4 4 4-4', 'M4 20h16'],
  upload: ['M12 20V10', 'M8 13l4-4 4 4', 'M4 4h16'],
  trash: ['M4 7h16', 'M9 7V5h6v2', 'M6.5 7l1 13h9l1-13'],
  edit: ['M4 20h4L18 10a2.8 2.8 0 1 0-4-4L4 16z', 'M13.5 6.5l4 4'],
  logout: ['M15 12H4', 'M8 8l-4 4 4 4', 'M14 4h5a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-5'],
  user: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5'],
  calendar: ['M4 6h16v14H4z', 'M4 10h16', 'M8 3v4', 'M16 3v4'],
  check: ['M5 13l4.5 4.5L19 7'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  refresh: ['M20.5 12a8.5 8.5 0 1 1-2.6-6.1', 'M20.5 3.5v5h-5'],
  chart: ['M3 21h18', 'M6 21V11', 'M11 21V4', 'M16 21v-6', 'M21 21v-9'],
  clock: ['M12 4a8 8 0 1 1 0 16 8 8 0 0 1 0-16z', 'M12 8v4.2l3 1.8'],
  chevron: ['M6 9l6 6 6-6'],
  folder: ['M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'],
  link: ['M10 13a5 5 0 0 0 7 0l2-2a5 5 0 1 0-7-7l-1 1', 'M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 1 0 7 7l1-1']
}
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, i) in paths[name] || []" :key="i" :d="d" />
  </svg>
</template>
