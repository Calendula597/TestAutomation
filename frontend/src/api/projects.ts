import { apiGet, apiSend } from "./http";

export interface ProjectRow {
  id: number;
  name: string;
  description?: string | null;
  status: "ACTIVE" | "ARCHIVED";
  myRole: "OWNER" | "MEMBER" | "VIEWER";
}

export interface MemberRow {
  userId: number;
  username: string;
  role: "OWNER" | "MEMBER" | "VIEWER";
  displayName?: string | null;
}

export async function fetchProjects(): Promise<ProjectRow[]> {
  return apiGet<ProjectRow[]>("/api/v1/projects");
}

export async function createProject(
  name: string,
  description: string,
): Promise<ProjectRow> {
  return apiSend<ProjectRow>("POST", "/api/v1/projects", { name, description });
}

export async function archiveProject(id: number): Promise<ProjectRow> {
  return apiSend<ProjectRow>("PUT", `/api/v1/projects/${id}/archive`);
}

export async function fetchProject(id: number): Promise<ProjectRow> {
  return apiGet<ProjectRow>(`/api/v1/projects/${id}`);
}

export async function fetchMembers(id: number): Promise<MemberRow[]> {
  return apiGet<MemberRow[]>(`/api/v1/projects/${id}/members`);
}

export async function addMember(
  id: number,
  username: string,
  role: "OWNER" | "MEMBER" | "VIEWER",
): Promise<MemberRow> {
  return apiSend<MemberRow>("POST", `/api/v1/projects/${id}/members`, {
    username,
    role,
  });
}

export async function changeMemberRole(
  id: number,
  userId: number,
  role: "OWNER" | "MEMBER" | "VIEWER",
): Promise<MemberRow> {
  return apiSend<MemberRow>(
    "PUT",
    `/api/v1/projects/${id}/members/${userId}/role`,
    { role },
  );
}

export async function removeMember(id: number, userId: number): Promise<void> {
  await apiSend<void>("DELETE", `/api/v1/projects/${id}/members/${userId}`);
}
