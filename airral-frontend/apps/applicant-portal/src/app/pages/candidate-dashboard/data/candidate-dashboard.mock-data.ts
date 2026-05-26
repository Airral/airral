import {
  CareerEvent,
  JobRoom,
  RecommendedRole,
  WorkspacePost
} from '../models/candidate-dashboard.models';

export const RECOMMENDED_ROLES: RecommendedRole[] = [
  {
    title: 'Product Frontend Engineer',
    company: 'Northstar Labs',
    location: 'Remote US',
    workMode: 'Remote',
    match: 94,
    salary: '$145k-$175k',
    posted: '2h ago',
    applicants: 34,
    reviewScore: 4.4,
    reviewCount: 128,
    connections: 7,
    easyApply: true,
    companyInsight: 'Product team grew 18% after Series B funding.',
    interviewSignal: 'Take-home + system design shared by peers.',
    tags: ['Angular', 'Design Systems', 'SaaS']
  },
  {
    title: 'Staff UI Engineer',
    company: 'Orbit Health',
    location: 'San Francisco, CA',
    workMode: 'Hybrid',
    match: 89,
    salary: '$165k-$205k',
    posted: '1d ago',
    applicants: 58,
    reviewScore: 4.1,
    reviewCount: 94,
    connections: 3,
    easyApply: true,
    companyInsight: 'Staff UI role aligns with clinical AI launch.',
    interviewSignal: 'Manager screen then product architecture panel.',
    tags: ['TypeScript', 'Accessibility', 'Platform']
  },
  {
    title: 'Frontend Platform Lead',
    company: 'Luma Commerce',
    location: 'Austin, TX',
    workMode: 'On-site',
    match: 84,
    salary: '$150k-$190k',
    posted: '3d ago',
    applicants: 76,
    reviewScore: 3.9,
    reviewCount: 211,
    connections: 5,
    easyApply: false,
    companyInsight: 'Marketplace relaunch needs web leadership.',
    interviewSignal: 'Peers report portfolio review as the key step.',
    tags: ['React', 'Node.js', 'GraphQL']
  }
];

export const WORKSPACE_POSTS: WorkspacePost[] = [
  {
    postType: 'COMPANY_SIGNAL',
    signalType: 'HIRING_PULSE',
    author: 'Northstar Labs',
    role: 'Company signal',
    room: 'Northstar Labs',
    lens: 'for_you',
    icon: 'domain',
    title: 'Northstar Labs just opened 3 new Engineering roles',
    body: 'The product team is expanding after their Series B funding round. New roles include Frontend, Backend, and Fullstack engineers.',
    whyRecommended: 'You have a high match score for their Frontend Engineer role.',
    depthScore: 95,
    freshnessMinutes: 12,
    tags: ['Hiring Growth', 'Series B', 'Remote'],
    replies: 5,
    saves: 12,
    action: 'View roles'
  },
  {
    author: 'Maya Chen',
    role: 'Frontend Engineer',
    room: 'Angular Interview Loop',
    lens: 'for_you',
    icon: 'rate_review',
    title: 'Can someone review my Northstar Labs take-home plan?',
    body: 'I have the prompt and a 3-hour window. Looking for feedback on scope, accessibility notes, and what to explain in the README.',
    whyRecommended: 'You follow Angular interview rooms and asked for take-home help this week.',
    depthScore: 92,
    freshnessMinutes: 21,
    tags: ['Take-home', 'Angular', 'Review'],
    replies: 14,
    saves: 9,
    action: 'Help review'
  },
  {
    author: 'Dev Patel',
    role: 'Product Designer',
    room: 'Startup Salary Watch',
    lens: 'following',
    icon: 'payments',
    title: 'Orbit Health shared comp bands during screen',
    body: 'Recruiter confirmed $165k-$205k base for Staff UI Engineer. Interview loop is manager screen, system design, then panel.',
    whyRecommended: 'People you follow saved this salary post and replied with interview-stage notes.',
    depthScore: 88,
    freshnessMinutes: 48,
    tags: ['Salary', 'Interview intel', 'Health tech'],
    replies: 22,
    saves: 31,
    action: 'Save intel'
  },
  {
    author: 'AIRRAL Coach',
    role: 'Weekly sprint',
    room: 'Remote SaaS Search',
    lens: 'for_you',
    icon: 'rocket_launch',
    title: '45-minute application sprint starts at 3 PM',
    body: 'Bring two roles, one outreach draft, and one blocker. The group leaves with a cleaner application and a tracked next step.',
    whyRecommended: 'You joined Remote SaaS Search and completed last week’s sprint checklist.',
    depthScore: 81,
    freshnessMinutes: 11,
    tags: ['Sprint', 'Remote', 'Accountability'],
    replies: 8,
    saves: 16,
    action: 'Join sprint'
  }
];

export const CAREER_EVENTS: CareerEvent[] = [
  {
    title: 'Recently Funded Startups Hiring Now',
    host: 'AIRRAL Radar',
    time: 'Tue, 5:30 PM',
    format: 'Live room',
    attendees: 128,
    action: 'Reserve'
  },
  {
    title: 'Frontend Portfolio Review',
    host: 'Peer critique circle',
    time: 'Wed, 2:00 PM',
    format: 'Small group',
    attendees: 24,
    action: 'Join'
  },
  {
    title: 'Recruiter Office Hours: Remote SaaS',
    host: 'Community partners',
    time: 'Fri, 12:00 PM',
    format: 'AMA',
    attendees: 73,
    action: 'Ask'
  }
];

export const JOB_ROOMS: JobRoom[] = [
  {
    name: 'Northstar Labs Room',
    focus: 'People applying to funded product engineering roles',
    members: 214,
    activity: '18 new posts today',
    trust: 'Salary bands and interview stages shared',
    liveNow: 23,
    tags: ['Series B', 'Angular', 'Remote']
  },
  {
    name: 'Remote Frontend Sprint',
    focus: 'Daily accountability for remote UI engineers',
    members: 482,
    activity: 'Live sprint in 2 hours',
    trust: 'Resume reviews and outreach swaps',
    liveNow: 41,
    tags: ['Frontend', 'Remote', 'Peer help']
  },
  {
    name: 'NYC Startup Events',
    focus: 'Hiring events, meetups, and founder AMAs',
    members: 156,
    activity: '3 events added this week',
    trust: 'Event follow-up templates included',
    liveNow: 9,
    tags: ['Events', 'Local', 'Networking']
  }
];
