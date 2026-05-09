import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

interface ScorecardCriterion {
  id: number;
  category: string;
  criterion: string;
  rating: number;
  notes: string;
  weight: number;
}

interface InterviewScorecard {
  candidateName: string;
  position: string;
  interviewer: string;
  date: string;
  interviewType: string;
  criteria: ScorecardCriterion[];
  overallNotes: string;
  recommendation: 'Strong Hire' | 'Hire' | 'No Hire' | 'Strong No Hire' | '';
}

@Component({
  selector: 'app-interview-scorecard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './interview-scorecard.component.html',
  styleUrl: './interview-scorecard.component.css',
})
export class InterviewScorecardComponent implements OnInit {
  scorecard: InterviewScorecard = {
    candidateName: '',
    position: '',
    interviewer: '',
    date: new Date().toISOString().split('T')[0],
    interviewType: 'Technical',
    criteria: [],
    overallNotes: '',
    recommendation: ''
  };

  readonly interviewTypes = ['Technical', 'Behavioral', 'System Design', 'Cultural Fit', 'Panel'];
  readonly recommendations = ['Strong Hire', 'Hire', 'No Hire', 'Strong No Hire'];

  constructor(
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit(): void {
    // Get candidate info from route params
    this.route.queryParams.subscribe(params => {
      if (params['candidateId']) {
        this.loadCandidateInfo(params['candidateId']);
      }
    });

    this.initializeCriteria();
  }

  loadCandidateInfo(candidateId: string): void {
    // TODO: Load from API
    this.scorecard.candidateName = 'Sarah Johnson';
    this.scorecard.position = 'Senior Frontend Engineer';
    this.scorecard.interviewer = 'John Doe (You)';
  }

  initializeCriteria(): void {
    this.scorecard.criteria = [
      // Technical Skills
      { id: 1, category: 'Technical Skills', criterion: 'Coding proficiency', rating: 0, notes: '', weight: 3 },
      { id: 2, category: 'Technical Skills', criterion: 'Problem-solving ability', rating: 0, notes: '', weight: 3 },
      { id: 3, category: 'Technical Skills', criterion: 'System design knowledge', rating: 0, notes: '', weight: 2 },
      { id: 4, category: 'Technical Skills', criterion: 'Technology stack expertise', rating: 0, notes: '', weight: 2 },

      // Communication
      { id: 5, category: 'Communication', criterion: 'Clarity of explanation', rating: 0, notes: '', weight: 2 },
      { id: 6, category: 'Communication', criterion: 'Listening and comprehension', rating: 0, notes: '', weight: 1 },
      { id: 7, category: 'Communication', criterion: 'Asking clarifying questions', rating: 0, notes: '', weight: 1 },

      // Culture Fit
      { id: 8, category: 'Culture Fit', criterion: 'Team collaboration mindset', rating: 0, notes: '', weight: 2 },
      { id: 9, category: 'Culture Fit', criterion: 'Growth mindset and learning', rating: 0, notes: '', weight: 2 },
      { id: 10, category: 'Culture Fit', criterion: 'Alignment with company values', rating: 0, notes: '', weight: 2 },

      // Experience
      { id: 11, category: 'Experience', criterion: 'Relevant experience depth', rating: 0, notes: '', weight: 2 },
      { id: 12, category: 'Experience', criterion: 'Project complexity handled', rating: 0, notes: '', weight: 2 },
    ];
  }

  get groupedCriteria(): { category: string; items: ScorecardCriterion[] }[] {
    const groups = this.scorecard.criteria.reduce((acc, criterion) => {
      if (!acc[criterion.category]) {
        acc[criterion.category] = [];
      }
      acc[criterion.category].push(criterion);
      return acc;
    }, {} as Record<string, ScorecardCriterion[]>);

    return Object.entries(groups).map(([category, items]) => ({ category, items }));
  }

  get weightedScore(): number {
    const totalWeight = this.scorecard.criteria.reduce((sum, c) => sum + c.weight, 0);
    const weightedSum = this.scorecard.criteria.reduce((sum, c) => sum + (c.rating * c.weight), 0);
    return totalWeight > 0 ? (weightedSum / totalWeight) : 0;
  }

  get completionPercentage(): number {
    const ratedCriteria = this.scorecard.criteria.filter(c => c.rating > 0).length;
    return (ratedCriteria / this.scorecard.criteria.length) * 100;
  }

  get isComplete(): boolean {
    return this.completionPercentage === 100 &&
           this.scorecard.recommendation !== '' &&
           this.scorecard.overallNotes.trim() !== '';
  }

  getRatingLabel(rating: number): string {
    const labels: Record<number, string> = {
      1: 'Poor',
      2: 'Below Average',
      3: 'Average',
      4: 'Good',
      5: 'Excellent'
    };
    return labels[rating] || 'Not Rated';
  }

  getRatingClass(rating: number): string {
    if (rating >= 4) return 'rating-high';
    if (rating >= 3) return 'rating-medium';
    if (rating > 0) return 'rating-low';
    return '';
  }

  saveDraft(): void {
    // TODO: Save to API as draft
    console.log('Saving draft:', this.scorecard);
    alert('Scorecard saved as draft');
  }

  submitScorecard(): void {
    if (!this.isComplete) {
      alert('Please complete all ratings, add overall notes, and select a recommendation before submitting.');
      return;
    }

    // TODO: Submit to API
    console.log('Submitting scorecard:', this.scorecard);
    alert('Scorecard submitted successfully!');
    this.router.navigate(['/interviews']);
  }

  cancel(): void {
    if (confirm('Are you sure you want to cancel? Unsaved changes will be lost.')) {
      this.router.navigate(['/interviews']);
    }
  }
}
