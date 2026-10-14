import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { setUnauthorizedHandler } from "../api/client";
import { Api, type Me } from "../api/endpoints";

interface AuthState {
  /** undefined while checking the session, null when logged out. */
  me: Me | null | undefined;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  isAdmin: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null | undefined>(undefined);

  useEffect(() => {
    setUnauthorizedHandler(() => setMe(null));
    // Any failure (usually 401: no session) shows the login page.
    Api.me()
      .then(setMe)
      .catch(() => setMe(null));
    return () => setUnauthorizedHandler(null);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    setMe(await Api.login(email, password));
  }, []);

  const logout = useCallback(async () => {
    await Api.logout().catch(() => undefined);
    setMe(null);
  }, []);

  const value = useMemo(() => ({ me, login, logout, isAdmin: me?.role === "admin" }), [me, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth outside AuthProvider");
  return value;
}
