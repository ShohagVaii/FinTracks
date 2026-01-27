
import React, { useState } from 'react';
import { 
  User, Download, Upload, Shield, Database, ChevronRight, X, Edit3, 
  Lock, Save, Trash2, Filter, Languages, Coins, Palette, Grid, Bell, Clock, Key, Layout, Mail, Globe, Star, Sun, Moon, Check, Plus, AlertCircle, Camera
} from 'lucide-react';
import { 
  Transaction, Category, UserProfile, Budget, RecurringTransaction, 
  Goal, Debt, Subscription, CurrencyCode, LanguageCode, ThemeColor, DateFormat, Loan 
} from '../types';
import { CURRENCIES, LANGUAGES } from '../constants';
import { v4 as uuidv4 } from 'uuid';

interface Props {
  transactions: Transaction[]; categories: Category[]; budgets: Budget[];
  recurring: RecurringTransaction[]; goals: Goal[]; debts: Debt[];
  subs: Subscription[]; profile: UserProfile; loans: Loan[];
  onUpdateProfile: (profile: UserProfile) => void;
  onImportData: (data: any) => void;
  onAddCategory: (cat: Category) => void;
}

const SettingsView: React.FC<Props> = (props) => {
  const [activeModal, setActiveModal] = useState<'profile' | 'pin' | 'appearance' | 'format' | 'reminders' | 'categories' | 'backup' | null>(null);
  
  const [nameInput, setNameInput] = useState(props.profile.name);
  const [usernameInput, setUsernameInput] = useState(props.profile.username || '');
  const [emailInput, setEmailInput] = useState(props.profile.email || '');
  const [avatarInput, setAvatarInput] = useState(props.profile.avatar || '');
  const [pinInput, setPinInput] = useState(props.profile.pin || '');
  
  const [newCatName, setNewCatName] = useState('');
  const [newCatIcon, setNewCatIcon] = useState('📦');

  const themeColors: { id: ThemeColor; class: string; name: string }[] = [
    { id: 'sky', class: 'bg-sky-500', name: 'Sky' },
    { id: 'emerald', class: 'bg-emerald-500', name: 'Emerald' },
    { id: 'rose', class: 'bg-rose-500', name: 'Rose' },
    { id: 'amber', class: 'bg-amber-500', name: 'Amber' },
  ];

  const handleSaveProfile = () => {
    props.onUpdateProfile({ 
      ...props.profile, 
      name: nameInput, 
      username: usernameInput,
      email: emailInput, 
      avatar: avatarInput 
    });
    setActiveModal(null);
  };

  const handleBackup = () => {
    const data = {
      profile: props.profile,
      transactions: props.transactions,
      categories: props.categories,
      budgets: props.budgets,
      recurring: props.recurring,
      goals: props.goals,
      debts: props.debts,
      subs: props.subs,
      loans: props.loans
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `FinTrack_Backup_${new Date().toISOString().split('T')[0]}.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const handleFileImport = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target?.result as string);
        props.onImportData(data);
        alert('Data successfully restored!');
      } catch (err) {
        alert('Failed to parse backup file.');
      }
    };
    reader.readAsText(file);
  };

  const handleReset = () => {
    if (confirm('আপনি কি নিশ্চিত যে সমস্ত ডাটা মুছে ফেলতে চান? এটি আর ফিরে পাওয়া যাবে না!')) {
      // Clear all related keys explicitly
      const keys = [
        'sritisetu_profile', 'sritisetu_transactions', 'sritisetu_categories',
        'sritisetu_budgets', 'sritisetu_recurring', 'sritisetu_goals',
        'sritisetu_debts', 'sritisetu_loans', 'sritisetu_subs',
        'sritisetu_investments', 'sritisetu_bills', 'sritisetu_mess_members',
        'sritisetu_mess_bazaars', 'sritisetu_mess_meals', 'sritisetu_shopping'
      ];
      keys.forEach(k => localStorage.removeItem(k));
      localStorage.clear();
      window.location.reload();
    }
  };

  const isEn = props.profile.language === 'en';
  const themePrimary = props.profile.themeColor || 'sky';
  const accentColor = `text-${themePrimary}-500`;
  const accentBg = `bg-${themePrimary}-500/10`;

  return (
    <div className="space-y-6 pb-28 animate-slide-up">
      <header className="px-2">
        <h2 className="text-3xl font-black">{isEn ? 'Settings' : 'সেটিংস ও প্রোফাইল'}</h2>
        <p className="text-[10px] text-slate-500 font-bold uppercase tracking-widest mt-1">FinTrack Finance Premium</p>
      </header>

      {/* User Profile Summary */}
      <div onClick={() => setActiveModal('profile')} className={`glass p-8 rounded-[2.5rem] border-${themePrimary}-500/20 bg-gradient-to-r from-${themePrimary}-500/10 to-transparent cursor-pointer group relative overflow-hidden flex items-center justify-between`}>
        <div className="flex items-center gap-6 relative z-10">
          <div className={`w-16 h-16 rounded-2xl bg-slate-800 border border-white/10 flex items-center justify-center overflow-hidden shadow-xl`}>
            {props.profile.avatar ? <img src={props.profile.avatar} className="w-full h-full object-cover" /> : <User size={32} className={accentColor} />}
          </div>
          <div>
            <h3 className="text-xl font-black text-white">{props.profile.name} <Edit3 size={14} className="inline ml-1 text-slate-500" /></h3>
            <p className="text-xs text-slate-400">@{props.profile.username || 'user'}</p>
          </div>
        </div>
        <ChevronRight size={20} className="text-slate-600 group-hover:translate-x-1 transition-transform" />
      </div>

      <div className="grid grid-cols-1 gap-6">
        {/* Personalization */}
        <section className="space-y-2">
          <h4 className="text-[10px] font-black text-slate-500 uppercase tracking-widest px-4">{isEn ? 'Personalization' : 'ব্যক্তিগত পছন্দ'}</h4>
          <div className="glass rounded-[2rem] overflow-hidden divide-y divide-white/5 border border-white/5">
             <button onClick={() => setActiveModal('appearance')} className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all text-left">
                <div className="flex items-center gap-4">
                   <Palette size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Appearance' : 'অ্যাপের চেহারা (ডার্ক/লাইট)'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
             <button onClick={() => setActiveModal('categories')} className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all text-left">
                <div className="flex items-center gap-4">
                   <Grid size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Categories' : 'ক্যাটাগরি ম্যানেজমেন্ট'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
             <div className="p-5 flex items-center justify-between">
                <div className="flex items-center gap-4">
                   <Globe size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Language' : 'ভাষা নির্বাচন'}</span>
                </div>
                <select 
                  value={props.profile.language || 'bn'} 
                  onChange={(e) => props.onUpdateProfile({...props.profile, language: e.target.value as LanguageCode})} 
                  className="bg-slate-900 border border-white/10 rounded-xl p-2 text-xs font-bold"
                >
                  {LANGUAGES.map(l => <option key={l.code} value={l.code}>{l.label}</option>)}
                </select>
             </div>
          </div>
        </section>

        {/* Security & System */}
        <section className="space-y-2">
          <h4 className="text-[10px] font-black text-slate-500 uppercase tracking-widest px-4">{isEn ? 'Security' : 'সিকিউরিটি ও সিস্টেম'}</h4>
          <div className="glass rounded-[2rem] overflow-hidden divide-y divide-white/5 border border-white/5">
             <button onClick={() => setActiveModal('pin')} className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all text-left">
                <div className="flex items-center gap-4">
                   <Lock size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'App Lock' : 'পিন লক প্রোটেকশন'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
             <button onClick={() => setActiveModal('format')} className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all text-left">
                <div className="flex items-center gap-4">
                   <Coins size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Currency & Format' : 'মুদ্রা ও তারিখ ফরম্যাট'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
             <button onClick={() => setActiveModal('reminders')} className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all text-left">
                <div className="flex items-center gap-4">
                   <Bell size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Reminders' : 'দৈনিক রিমাইন্ডার'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
          </div>
        </section>

        {/* Data & Backup */}
        <section className="space-y-2">
          <h4 className="text-[10px] font-black text-slate-500 uppercase tracking-widest px-4">{isEn ? 'Data' : 'ডাটা ব্যাকআপ ও রিসেট'}</h4>
          <div className="glass rounded-[2rem] overflow-hidden divide-y divide-white/5 border border-white/5">
             <button onClick={handleBackup} className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all text-left">
                <div className="flex items-center gap-4">
                   <Download size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Full Backup' : 'সম্পুর্ন ডাটা ব্যাকআপ নিন'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
             <label className="w-full p-5 flex justify-between items-center hover:bg-white/5 transition-all cursor-pointer">
                <div className="flex items-center gap-4">
                   <Upload size={18} className={accentColor} />
                   <span className="text-sm font-bold">{isEn ? 'Restore Backup' : 'ডাটা ইমপোর্ট করুন'}</span>
                </div>
                <input type="file" className="hidden" accept=".json" onChange={handleFileImport} />
                <ChevronRight size={16} className="text-slate-700" />
             </label>
             <button onClick={handleReset} className="w-full p-5 flex justify-between items-center hover:bg-rose-500/5 transition-all text-left group">
                <div className="flex items-center gap-4">
                   <Trash2 size={18} className="text-rose-400" />
                   <span className="text-sm font-bold">{isEn ? 'Reset App' : 'অ্যাপ রিসেট করুন (মুছে ফেলুন)'}</span>
                </div>
                <ChevronRight size={16} className="text-slate-700" />
             </button>
          </div>
        </section>
      </div>

      {/* --- Modals for Each Section --- */}

      {/* Profile Edit Modal */}
      {activeModal === 'profile' && (
        <div className="fixed inset-0 z-[400] bg-black/80 flex items-center justify-center p-4 backdrop-blur-md">
          <div className="glass w-full max-w-sm rounded-[2.5rem] p-8 animate-slide-up relative">
            <button onClick={() => setActiveModal(null)} className="absolute top-6 right-6"><X/></button>
            <h3 className="text-lg font-black mb-6">প্রোফাইল সেটিংস</h3>
            <div className="space-y-4">
              <input value={nameInput} onChange={e=>setNameInput(e.target.value)} placeholder="নাম" className="w-full bg-white/5 border border-white/10 rounded-2xl p-4 text-sm outline-none"/>
              <input value={usernameInput} onChange={e=>setUsernameInput(e.target.value)} placeholder="ইউজারনেম" className="w-full bg-white/5 border border-white/10 rounded-2xl p-4 text-sm outline-none"/>
              <input value={emailInput} onChange={e=>setEmailInput(e.target.value)} placeholder="ইমেইল" className="w-full bg-white/5 border border-white/10 rounded-2xl p-4 text-sm outline-none"/>
              <input value={avatarInput} onChange={e=>setAvatarInput(e.target.value)} placeholder="ছবি URL" className="w-full bg-white/5 border border-white/10 rounded-2xl p-4 text-sm outline-none"/>
              <button onClick={handleSaveProfile} className={`w-full py-4 bg-${themePrimary}-500 rounded-2xl font-bold`}>আপডেট করুন</button>
            </div>
          </div>
        </div>
      )}

      {/* Appearance Modal */}
      {activeModal === 'appearance' && (
        <div className="fixed inset-0 z-[400] bg-black/80 flex items-center justify-center p-4 backdrop-blur-md">
          <div className="glass w-full max-w-sm rounded-[2.5rem] p-8 animate-slide-up relative">
            <button onClick={() => setActiveModal(null)} className="absolute top-6 right-6"><X/></button>
            <h3 className="text-lg font-black mb-6">চেহারা ও মোড</h3>
            <div className="space-y-6">
               <div className="flex items-center justify-between p-4 glass rounded-2xl">
                  <span className="font-bold">ডার্ক মোড</span>
                  <button 
                    onClick={() => props.onUpdateProfile({ ...props.profile, theme: props.profile.theme === 'dark' ? 'light' : 'dark' })}
                    className={`w-14 h-8 rounded-full relative transition-all ${props.profile.theme === 'dark' ? 'bg-sky-500' : 'bg-slate-700'}`}
                  >
                    <div className={`absolute top-1 w-6 h-6 rounded-full bg-white transition-all ${props.profile.theme === 'dark' ? 'right-1' : 'left-1'}`} />
                  </button>
               </div>
               <div className="space-y-3">
                  <span className="text-[10px] font-black uppercase text-slate-500 block text-center">থিম কালার সিলেক্ট করুন</span>
                  <div className="flex justify-between px-2">
                    {themeColors.map(color => (
                      <button 
                        key={color.id} 
                        onClick={() => props.onUpdateProfile({ ...props.profile, themeColor: color.id })}
                        className={`w-12 h-12 rounded-2xl ${color.class} flex items-center justify-center ${props.profile.themeColor === color.id ? 'scale-110 border-4 border-white' : 'opacity-40'}`}
                      >
                        {props.profile.themeColor === color.id && <Check size={18} className="text-white" />}
                      </button>
                    ))}
                  </div>
               </div>
            </div>
          </div>
        </div>
      )}

      {/* Security PIN Modal */}
      {activeModal === 'pin' && (
        <div className="fixed inset-0 z-[400] bg-black/80 flex items-center justify-center p-4 backdrop-blur-md">
          <div className="glass w-full max-w-sm rounded-[2.5rem] p-8 animate-slide-up relative">
            <button onClick={() => setActiveModal(null)} className="absolute top-6 right-6"><X/></button>
            <h3 className="text-lg font-black mb-6">অ্যাপ লক</h3>
            <div className="space-y-6">
               <div className="flex items-center justify-between p-4 glass rounded-2xl">
                  <span className="font-bold">পিন লক অন করুন</span>
                  <button 
                    onClick={() => props.onUpdateProfile({ ...props.profile, isPinEnabled: !props.profile.isPinEnabled })}
                    className={`w-12 h-6 rounded-full relative transition-all ${props.profile.isPinEnabled ? 'bg-sky-500' : 'bg-white/10'}`}
                  >
                    <div className={`absolute top-1 w-4 h-4 rounded-full bg-white transition-all ${props.profile.isPinEnabled ? 'right-1' : 'left-1'}`} />
                  </button>
               </div>
               <input 
                 type="password" 
                 maxLength={4}
                 value={pinInput} 
                 onChange={e=>setPinInput(e.target.value)} 
                 placeholder="নতুন ৪ সংখ্যার পিন দিন"
                 className="w-full bg-white/5 border border-white/10 rounded-2xl p-4 text-center text-2xl font-black outline-none"
               />
               <button 
                onClick={() => {props.onUpdateProfile({...props.profile, pin: pinInput}); setActiveModal(null); alert('পিন সেভ করা হয়েছে');}}
                className={`w-full py-4 bg-sky-500 rounded-2xl font-bold`}
               >
                পিন আপডেট করুন
               </button>
            </div>
          </div>
        </div>
      )}

      {/* Format Modal */}
      {activeModal === 'format' && (
        <div className="fixed inset-0 z-[400] bg-black/80 flex items-center justify-center p-4 backdrop-blur-md">
          <div className="glass w-full max-w-sm rounded-[2.5rem] p-8 animate-slide-up relative">
            <button onClick={() => setActiveModal(null)} className="absolute top-6 right-6"><X/></button>
            <h3 className="text-lg font-black mb-6">কারেন্সি ও তারিখ</h3>
            <div className="space-y-4">
               <select 
                 value={props.profile.baseCurrency}
                 onChange={(e) => props.onUpdateProfile({ ...props.profile, baseCurrency: e.target.value as CurrencyCode })}
                 className="w-full bg-slate-900 border border-white/10 rounded-2xl p-4 font-bold outline-none"
               >
                 {CURRENCIES.map(c => <option key={c.code} value={c.code}>{c.symbol} ({c.code}) - {c.name}</option>)}
               </select>
               <select 
                 value={props.profile.dateFormat}
                 onChange={(e) => props.onUpdateProfile({ ...props.profile, dateFormat: e.target.value as DateFormat })}
                 className="w-full bg-slate-900 border border-white/10 rounded-2xl p-4 font-bold outline-none"
               >
                 <option value="DD-MM-YYYY">DD-MM-YYYY</option>
                 <option value="YYYY-MM-DD">YYYY-MM-DD</option>
                 <option value="MM/DD/YYYY">MM/DD/YYYY</option>
               </select>
            </div>
          </div>
        </div>
      )}

      {/* Categories Modal */}
      {activeModal === 'categories' && (
        <div className="fixed inset-0 z-[400] bg-black/80 flex items-center justify-center p-4 backdrop-blur-md">
          <div className="glass w-full max-w-sm rounded-[2.5rem] p-8 animate-slide-up relative flex flex-col max-h-[80vh]">
            <button onClick={() => setActiveModal(null)} className="absolute top-6 right-6"><X/></button>
            <h3 className="text-lg font-black mb-6">ক্যাটাগরি লিস্ট</h3>
            <div className="flex-1 overflow-y-auto space-y-2 mb-6 no-scrollbar">
               {props.categories.map(cat => (
                 <div key={cat.id} className="flex items-center justify-between p-3 glass rounded-xl">
                    <div className="flex items-center gap-3">
                       <span className="text-xl">{cat.icon}</span>
                       <span className="text-sm font-bold">{cat.name}</span>
                    </div>
                 </div>
               ))}
            </div>
            <div className="p-4 bg-slate-900/50 rounded-2xl space-y-3">
               <div className="flex gap-2">
                  <input value={newCatIcon} onChange={e=>setNewCatIcon(e.target.value)} placeholder="Icon" className="w-12 bg-white/5 border border-white/10 rounded-xl p-3 text-center"/>
                  <input value={newCatName} onChange={e=>setNewCatName(e.target.value)} placeholder="ক্যাটাগরি নাম" className="flex-1 bg-white/5 border border-white/10 rounded-xl p-3 text-sm"/>
               </div>
               <button 
                  onClick={() => {if(!newCatName) return; props.onAddCategory({ id: uuidv4(), name: newCatName, icon: newCatIcon, color: 'bg-sky-500' }); setNewCatName('');}}
                  className="w-full py-3 bg-sky-500 rounded-xl font-bold flex items-center justify-center gap-2"
               >
                  <Plus size={16}/> যোগ করুন
               </button>
            </div>
          </div>
        </div>
      )}

      {/* Reminders Modal */}
      {activeModal === 'reminders' && (
        <div className="fixed inset-0 z-[400] bg-black/80 flex items-center justify-center p-4 backdrop-blur-md">
          <div className="glass w-full max-w-sm rounded-[2.5rem] p-8 animate-slide-up relative text-center">
            <button onClick={() => setActiveModal(null)} className="absolute top-6 right-6"><X/></button>
            <Bell size={40} className="mx-auto text-sky-400 mb-4" />
            <h3 className="text-lg font-black mb-2">ডেইলি রিমাইন্ডার</h3>
            <p className="text-xs text-slate-400 mb-6">প্রতিদিন আপনার খরচ এন্ট্রি করার জন্য অ্যাপ আপনাকে রিমাইন্ডার দিবে।</p>
            <input 
              type="time" 
              value={props.profile.reminderTime || '22:00'}
              onChange={(e) => props.onUpdateProfile({ ...props.profile, reminderTime: e.target.value })}
              className="bg-white/5 border border-white/10 rounded-2xl p-4 text-3xl font-black text-center text-white outline-none focus:border-sky-500 mb-6"
            />
            <button onClick={() => setActiveModal(null)} className="w-full py-4 bg-sky-500 rounded-2xl font-bold">সেভ করুন</button>
          </div>
        </div>
      )}
    </div>
  );
};

export default SettingsView;
