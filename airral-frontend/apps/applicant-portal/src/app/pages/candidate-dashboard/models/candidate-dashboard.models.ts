import { FeedPostType, FeedVisibility } from '@airral/shared-types';

export type DashboardView = 'jobs' | 'feed' | 'rooms' | 'messages' | 'events' | 'resume' | 'founders';

export interface RecommendedRole {
  sourceJobId?: string;
  sourceType?: string;
  sourceName?: string;
  sourceBoardToken?: string;
  externalJobId?: string;
  applyUrl?: string;
  jobUrl?: string;
  applyMode?: string;
  title: string;
  company: string;
  companyDomain?: string;
  companyLogoUrl?: string;
  location: string;
  workMode: string;
  match: number;
  salary: string;
  posted: string;
  applicants: number;
  reviewScore: number;
  reviewCount: number;
  connections: number;
  easyApply: boolean;
  jobQualityScore?: number;
  qualityReasons?: string[];
  totalCompLabel?: string;
  compensationConfidence?: string;
  companyInsight: string;
  interviewSignal: string;
  tags: string[];
}

export interface WorkspacePost {
  postType?: FeedPostType;
  signalType?: string;
  author: string;
  role: string;
  room: string;
  lens: 'for_you' | 'following';
  icon: string;
  title: string;
  body: string;
  whyRecommended: string;
  depthScore: number;
  freshnessMinutes: number;
  tags: string[];
  replies: number;
  saves: number;
  action: string;
  sourceName?: string;
  sourceUrl?: string;
  imageUrl?: string;
  confidence?: string;
  linkedJobsCount?: number;
}

export interface CommunityPostDraft {
  postType: FeedPostType;
  visibility: FeedVisibility;
  topic: string;
  content: string;
  targetLabel?: string;
}

export interface HiringSignal {
  company: string;
  signal: string;
  amount: string;
  source: string;
  confidence: 'High' | 'Medium' | 'Low';
  roles: string;
  whyNow: string;
  tags: string[];
}

export interface CareerEvent {
  title: string;
  host: string;
  time: string;
  format: string;
  attendees: number;
  action: string;
}

export interface JobRoom {
  name: string;
  focus: string;
  members: number;
  activity: string;
  trust: string;
  liveNow: number;
  tags: string[];
}
