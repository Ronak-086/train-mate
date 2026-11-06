"use client";

import { usePathname } from "next/navigation";
import { AppShell, BottomNav, type NavItem } from "@/components/ui";

const NAV: NavItem[] = [
  { id: "home", href: "/home", label: "Home" },
  { id: "board", href: "/board", label: "Board" },
  { id: "chats", href: "/chats", label: "Chats" },
  { id: "profile", href: "/profile", label: "Profile" },
];

export default function TabsLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const active = NAV.find((n) => pathname === n.href || pathname.startsWith(`${n.href}/`))?.id ?? "home";
  return (
    <AppShell>
      {children}
      <BottomNav items={NAV} activeId={active} />
    </AppShell>
  );
}
