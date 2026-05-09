import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';

type PostReaction = 'useful' | 'inspiring' | 'practical';

interface CompanyPost {
  id: number;
  company: string;
  headline: string;
  timeAgo: string;
  content: string;
  topic: string;
  likes: number;
  comments: number;
  reposts: number;
  selectedReaction?: PostReaction;
}

@Component({
  selector: 'app-candidate-feed',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatButtonModule, MatIconModule, MatDividerModule],
  templateUrl: './candidate-feed.component.html',
  styleUrls: ['./candidate-feed.component.css']
})
export class CandidateFeedComponent {
  @Input() viewerName = 'You';

  @Output() openJobsBoard = new EventEmitter<void>();
  @Output() openProfile = new EventEmitter<void>();

  companyPosts: CompanyPost[] = [
    {
      id: 1,
      company: 'AMD Enterprise',
      headline: 'Hardware Innovation Team · 1.2M followers',
      timeAgo: '2h',
      topic: 'Culture',
      content: 'Behind every product breakthrough is a team that keeps learning. Today our interns shipped their first optimization patch to production.',
      likes: 468,
      comments: 33,
      reposts: 21
    },
    {
      id: 2,
      company: 'Stripe',
      headline: 'Payments Infrastructure · 890K followers',
      timeAgo: '4h',
      topic: 'Hiring',
      content: 'We are hiring backend and platform engineers across APAC and EMEA. If you love reliability at scale, check open roles and apply in minutes.',
      likes: 712,
      comments: 58,
      reposts: 44
    },
    {
      id: 3,
      company: 'Canva',
      headline: 'Design Platform · 2.1M followers',
      timeAgo: '8h',
      topic: 'Career Growth',
      content: 'A portfolio is strongest when it shows decisions, not only outcomes. Share your process and the impact you created.',
      likes: 535,
      comments: 41,
      reposts: 29
    }
  ];

  toggleReaction(postId: number, reaction: PostReaction): void {
    const post = this.companyPosts.find((item) => item.id === postId);
    if (!post) {
      return;
    }

    if (post.selectedReaction === reaction) {
      post.selectedReaction = undefined;
      post.likes -= 1;
      return;
    }

    if (!post.selectedReaction) {
      post.likes += 1;
    }

    post.selectedReaction = reaction;
  }
}
