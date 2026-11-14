import { computed, reactive } from "vue";

/**
 * Login state shared across the app shell, pages and the router guard.
 */
const state = reactive({
  username: null as string | null,
  role: null as string | null,
  loaded: false,
});

export const session = {
  get username() {
    return state.username;
  },
  get role() {
    return state.role;
  },
  get loaded() {
    return state.loaded;
  },
  get isAdmin() {
    return state.role === "ADMIN";
  },
  setFrom(me: { username: string; role: string }) {
    state.username = me.username;
    state.role = me.role;
    state.loaded = true;
  },
  clear() {
    state.username = null;
    state.role = null;
    state.loaded = true;
  },
};

export const isAuthed = computed(() => session.username !== null);
