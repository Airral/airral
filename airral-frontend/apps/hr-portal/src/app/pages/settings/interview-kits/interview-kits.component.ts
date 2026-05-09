import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface InterviewQuestion {
  id: number;
  question: string;
  category: string;
  difficulty: 'Easy' | 'Medium' | 'Hard';
}

interface InterviewKit {
  id: number;
  name: string;
  role: string;
  duration: number;
  questions: InterviewQuestion[];
  isActive: boolean;
  description: string;
}

@Component({
  selector: 'app-interview-kits',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './interview-kits.component.html',
  styleUrl: './interview-kits.component.css',
})
export class InterviewKitsComponent {
  selectedKit: InterviewKit | null = null;
  editMode = false;
  editingKit: InterviewKit | null = null;

  kits: InterviewKit[] = [
    {
      id: 1,
      name: 'Frontend Engineer Loop',
      role: 'Software Engineer (Frontend)',
      duration: 60,
      isActive: true,
      description: 'Technical interview focused on React, TypeScript, and modern frontend architecture',
      questions: [
        { id: 1, question: 'Explain the virtual DOM and how React uses it for efficient rendering', category: 'React', difficulty: 'Medium' },
        { id: 2, question: 'How do you manage state in a large React application?', category: 'React', difficulty: 'Medium' },
        { id: 3, question: 'What are your strategies for optimizing web performance?', category: 'Performance', difficulty: 'Hard' },
        { id: 4, question: 'Explain TypeScript generics with a practical example', category: 'TypeScript', difficulty: 'Hard' },
        { id: 5, question: 'Walk through your approach to building a responsive layout', category: 'CSS', difficulty: 'Easy' },
      ]
    },
    {
      id: 2,
      name: 'Backend Engineer Loop',
      role: 'Software Engineer (Backend)',
      duration: 60,
      isActive: true,
      description: 'System design and backend architecture interview',
      questions: [
        { id: 6, question: 'Design a scalable URL shortener service', category: 'System Design', difficulty: 'Hard' },
        { id: 7, question: 'Explain CAP theorem and its real-world implications', category: 'Distributed Systems', difficulty: 'Hard' },
        { id: 8, question: 'How would you handle database migrations in production?', category: 'Database', difficulty: 'Medium' },
        { id: 9, question: 'Describe your approach to API versioning', category: 'API Design', difficulty: 'Medium' },
        { id: 10, question: 'What are common security vulnerabilities in web apps?', category: 'Security', difficulty: 'Medium' },
      ]
    },
    {
      id: 3,
      name: 'Product Manager Loop',
      role: 'Product Manager',
      duration: 45,
      isActive: true,
      description: 'Product sense and strategy assessment',
      questions: [
        { id: 11, question: 'How do you prioritize features when everything is "urgent"?', category: 'Prioritization', difficulty: 'Medium' },
        { id: 12, question: 'Walk me through launching a product from 0 to 1', category: 'Strategy', difficulty: 'Hard' },
        { id: 13, question: 'How do you measure product success?', category: 'Metrics', difficulty: 'Medium' },
        { id: 14, question: 'Describe a time you had to say "no" to a stakeholder', category: 'Communication', difficulty: 'Medium' },
        { id: 15, question: 'How do you balance tech debt vs. new features?', category: 'Trade-offs', difficulty: 'Hard' },
      ]
    },
    {
      id: 4,
      name: 'UX Designer Loop',
      role: 'UX Designer',
      duration: 45,
      isActive: true,
      description: 'Design thinking and portfolio review',
      questions: [
        { id: 16, question: 'Walk me through your design process for a recent project', category: 'Process', difficulty: 'Medium' },
        { id: 17, question: 'How do you handle conflicting feedback from stakeholders?', category: 'Collaboration', difficulty: 'Medium' },
        { id: 18, question: 'Explain your approach to user research', category: 'Research', difficulty: 'Easy' },
        { id: 19, question: 'Design a checkout flow for an e-commerce app', category: 'Design Exercise', difficulty: 'Hard' },
        { id: 20, question: 'How do you ensure accessibility in your designs?', category: 'Accessibility', difficulty: 'Medium' },
      ]
    },
    {
      id: 5,
      name: 'Behavioral Interview',
      role: 'All Roles',
      duration: 30,
      isActive: true,
      description: 'Culture fit and soft skills assessment',
      questions: [
        { id: 21, question: 'Tell me about a time you faced a significant challenge at work', category: 'Problem Solving', difficulty: 'Easy' },
        { id: 22, question: 'Describe a situation where you had to work with a difficult teammate', category: 'Teamwork', difficulty: 'Medium' },
        { id: 23, question: 'How do you handle tight deadlines and pressure?', category: 'Stress Management', difficulty: 'Easy' },
        { id: 24, question: 'What motivates you in your work?', category: 'Motivation', difficulty: 'Easy' },
        { id: 25, question: 'Tell me about a time you failed and what you learned', category: 'Growth Mindset', difficulty: 'Medium' },
      ]
    },
  ];

  viewKit(kit: InterviewKit): void {
    this.selectedKit = kit;
  }

  closeView(): void {
    this.selectedKit = null;
  }

  editKit(kit: InterviewKit): void {
    this.editingKit = { ...kit, questions: [...kit.questions] };
    this.editMode = true;
  }

  saveKit(): void {
    if (this.editingKit) {
      const index = this.kits.findIndex(k => k.id === this.editingKit!.id);
      if (index !== -1) {
        this.kits[index] = { ...this.editingKit };
      }
      // TODO: Call API to save
      console.log('Saving interview kit:', this.editingKit);
      this.cancelEdit();
    }
  }

  cancelEdit(): void {
    this.editMode = false;
    this.editingKit = null;
  }

  toggleKit(kit: InterviewKit): void {
    kit.isActive = !kit.isActive;
    // TODO: Call API to save
    console.log('Toggling kit:', kit);
  }

  getDifficultyClass(difficulty: string): string {
    const map: Record<string, string> = {
      'Easy': 'difficulty-easy',
      'Medium': 'difficulty-medium',
      'Hard': 'difficulty-hard'
    };
    return map[difficulty] || '';
  }
}
