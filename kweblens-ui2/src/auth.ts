// In-memory HTTP Basic credentials for write actions. Deliberately NOT persisted to
// storage — reload requires signing in again. kweblens is open-by-default and self-hosted;
// production must front it with a real identity provider (see SecurityConfig).
let basic: string | null = null;

export const auth = {
  set(user: string, pass: string) {
    basic = 'Basic ' + btoa(`${user}:${pass}`);
  },
  clear() {
    basic = null;
  },
  header(): Record<string, string> {
    return basic ? { Authorization: basic } : {};
  },
  isSet(): boolean {
    return basic !== null;
  },
};
