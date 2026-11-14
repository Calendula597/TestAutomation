import { apiGet, apiSend } from "./http";

export interface Me {
  username: string;
  role: string;
}

export interface SystemStatus {
  initialized: boolean;
}

export interface UserRow {
  id: number;
  username: string;
  role: string;
  status: string;
  displayName?: string | null;
}

export async function fetchMe(): Promise<Me> {
  return apiGet<Me>("/api/v1/auth/me");
}

export async function fetchSystemStatus(): Promise<SystemStatus> {
  return apiGet<SystemStatus>("/api/v1/system/status");
}

export async function login(username: string, password: string): Promise<Me> {
  return apiSend<Me>("POST", "/api/v1/auth/login", { username, password });
}

export async function logout(): Promise<void> {
  await apiSend<void>("POST", "/api/v1/auth/logout");
}

export async function initializeSystem(
  username: string,
  password: string,
): Promise<UserRow> {
  return apiSend<UserRow>("POST", "/api/v1/system/initialize", {
    username,
    password,
  });
}

export async function fetchUsers(): Promise<UserRow[]> {
  return apiGet<UserRow[]>("/api/v1/system/users");
}

export async function changeUserStatus(
  id: number,
  status: "ACTIVE" | "DISABLED",
): Promise<UserRow> {
  return apiSend<UserRow>("PUT", `/api/v1/system/users/${id}/status`, {
    status,
  });
}
