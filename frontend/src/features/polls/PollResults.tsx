import { Stack, Text } from '@mantine/core';
import type { Poll, PollResults as Results } from './queries';
import './polls.css';

export function PollResults({ poll, results }: { poll: Poll; results: Results }) {
  return <Stack gap="lg">
    {poll.questions.map((question) => {
      const counts = results.questions.find((item) => item.questionId === question.id);
      return <section key={question.id} className="poll-result" aria-label={question.text}>
        <Text fw={700}>{question.text}</Text>
        <Stack gap="xs" mt="xs">{question.options.map((option) => {
          const result = counts?.options.find((item) => item.optionId === option.id);
          const votes = result?.votes ?? 0, share = result?.share ?? 0;
          return <div key={option.id} className="poll-result-row">
            <div className="poll-result-label"><span>{option.text}</span><span className="cr-number">{votes} · {share} %</span></div>
            <div className="poll-bar" aria-hidden="true"><div className="poll-bar-fill" style={{ width: `${share}%` }} /></div>
          </div>;
        })}</Stack>
      </section>;
    })}
  </Stack>;
}
