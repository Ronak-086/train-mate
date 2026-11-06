"use client";

import { useParams } from "next/navigation";
import { PostDetail } from "@/components/post/PostDetail";

export default function PostPage() {
  const { id } = useParams<{ id: string }>();
  return <PostDetail id={id} />;
}
