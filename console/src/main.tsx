import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { Layout } from "./components/Layout";
import { AccountPage } from "./pages/AccountPage";
import { DevicesPage } from "./pages/DevicesPage";
import { EnrollPage } from "./pages/EnrollPage";
import { ExercisePage } from "./pages/exercise/ExercisePage";
import { ExercisesPage } from "./pages/ExercisesPage";
import { LoginPage } from "./pages/LoginPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { SettingsPage } from "./pages/settings/SettingsPage";
import { UnitsProvider } from "./units/UnitsContext";
import "maplibre-gl/dist/maplibre-gl.css";
import "./styles.css";

function AppRoutes() {
  const { me } = useAuth();
  if (window.location.pathname === "/reset") return <ResetPasswordPage />;
  if (me === undefined) return <p className="muted center">Loading…</p>;
  if (me === null) return <LoginPage />;
  return (
    <UnitsProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Navigate to="/exercises" replace />} />
          <Route path="exercises" element={<ExercisesPage />} />
          <Route path="exercises/:id/*" element={<ExercisePage />} />
          <Route path="devices" element={<DevicesPage />} />
          <Route path="enroll" element={<EnrollPage />} />
          <Route path="settings/*" element={<SettingsPage />} />
          <Route path="profiles" element={<Navigate to="/settings/monitors" replace />} />
          <Route path="instructors" element={<Navigate to="/settings/instructors" replace />} />
          <Route path="account" element={<AccountPage />} />
          <Route path="*" element={<Navigate to="/exercises" replace />} />
        </Route>
      </Routes>
    </UnitsProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
