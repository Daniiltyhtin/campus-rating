const paths = {
  home: 'M4 10.5 12 4l8 6.5V20h-5v-6H9v6H4z',
  tasks: 'M9 6h11M9 12h11M9 18h11M4 6l1 1 2-2M4 12l1 1 2-2M4 18l1 1 2-2',
  activities: 'M8 3v3M16 3v3M4 8h16M5 5h14v15H5zM9 13l2 2 4-4',
  awards: 'M8 4h8v5a4 4 0 0 1-8 0zM8 6H5a3 3 0 0 0 3 4M16 6h3a3 3 0 0 1-3 4M12 13v4M8 20h8',
  profile: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 20a8 8 0 0 1 16 0',
  checkpoints: 'M5 21V4M5 4h11l-2 4 2 4H5',
  groups: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 19a6 6 0 0 1 12 0M16 5a3 3 0 0 1 0 6M18 19a5 5 0 0 0-3-4.5',
  directory: 'M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11',
  users: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 19a6 6 0 0 1 12 0M17 8v6M14 11h6',
  import: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  semesters: 'M5 5h14v15H5zM8 3v4M16 3v4M5 10h14',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M14 5v4M8 15v4',
  budgets: 'M4 7h16v12H4zM4 7l2-3h12l2 3M16 13h2',
} as const;

export type NavIconName = keyof typeof paths;

export function NavIcon({ name }: { name: NavIconName }) {
  return <svg className="cr-nav-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>;
}
