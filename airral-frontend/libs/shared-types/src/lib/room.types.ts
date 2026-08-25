export type AirralRoomType = 'DIRECT' | 'JOB' | 'COMPANY' | 'FOUNDER' | 'EVENT' | 'FEED' | 'GENERAL' | string;
export type AirralRoomVisibility = 'PRIVATE' | 'AUTHENTICATED' | 'PUBLIC' | string;

export interface RoomMessageResponse {
  id: number;
  roomId: number;
  messageType: 'TEXT' | 'SYSTEM' | string;
  body: string;
  senderDisplayName: string;
  senderInitials: string;
  ownMessage: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface RoomResponse {
  id: number;
  roomType: AirralRoomType;
  visibility: AirralRoomVisibility;
  name: string;
  description?: string;
  topic?: string;
  targetType?: string;
  targetId?: string;
  targetLabel?: string;
  createdByDisplayName?: string;
  member?: boolean;
  memberRole?: string;
  memberCount?: number;
  lastMessageAt?: string;
  createdAt?: string;
  updatedAt?: string;
  recentMessages?: RoomMessageResponse[];
}

export interface CreateRoomRequest {
  roomType?: AirralRoomType;
  visibility?: AirralRoomVisibility;
  name: string;
  description?: string;
  topic?: string;
  targetType?: string;
  targetId?: string;
  targetLabel?: string;
  initialMessage?: string;
}

export interface CreateDirectRoomRequest {
  recipientUserId: number;
  initialMessage?: string;
}

export interface SendRoomMessageRequest {
  body: string;
}

export interface RoomInviteResponse {
  roomId: number;
  inviteToken: string;
  expiresAt?: string;
  maxUses?: number;
  usesCount?: number;
}
