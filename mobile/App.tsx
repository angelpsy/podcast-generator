import React from 'react';
import {StyleSheet, View} from 'react-native';
import {NavigationContainer, useNavigation} from '@react-navigation/native';
import {createNativeStackNavigator, type NativeStackNavigationProp} from '@react-navigation/native-stack';
import {BottomTabBar, createBottomTabNavigator, type BottomTabBarProps} from '@react-navigation/bottom-tabs';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import TopicsNavigator from './src/TopicsScreen';
import PlaylistsNavigator from './src/PlaylistsScreen';
import PodcastScreen from './src/PodcastScreen';
import PlayerBar from './src/PlayerBar';
import SettingsScreen from './src/SettingsScreen';
import GenerationQueueScreen from './src/GenerationQueueScreen';
import {useGenerationQueue} from './src/generationQueue';
import {AppProvider, Button, useApp, useNavigationTheme} from './src/theme';
type RootRoutes = {Home: undefined; Settings: undefined};
const Root = createNativeStackNavigator<RootRoutes>();
function SettingsAction() { const navigation = useNavigation<NativeStackNavigationProp<RootRoutes>>(); return <Button title="⚙" accessibilityLabel="Настройки" variant="quiet" onPress={() => navigation.navigate('Settings')} />; }
const Tabs = createBottomTabNavigator<{Topics: undefined; Podcasts: {create?: number} | undefined; GenerationQueue: undefined; Playlists: undefined}>();
const ignoreBusy = (_busy: boolean) => {};
function PodcastTab() { return <PodcastScreen onBusyChange={ignoreBusy} />; }
const renderSettingsAction = () => <SettingsAction />;
function AppTabBar(props: BottomTabBarProps) { return <View><PlayerBar /><BottomTabBar {...props} /></View>; }
const navStyles = StyleSheet.create({icon: {width: 24, height: 24, justifyContent: 'center', gap: 4}, layer: {width: 19, height: 12, borderWidth: 1.5, borderRadius: 2}, backLayer: {position: 'absolute', top: 2, left: 0}, frontLayer: {position: 'absolute', bottom: 2, right: 0}, bars: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 3}, bar: {width: 2, borderRadius: 1}, line: {height: 2, width: 21, borderRadius: 1}, clock: {width: 22, height: 22, borderRadius: 11, borderWidth: 1.5}, clockHand: {position: 'absolute', left: 9, top: 3, width: 2, height: 7}, clockMinute: {position: 'absolute', left: 9, top: 8, width: 6, height: 2}});
function NavIcon({color, kind}: {color: string; kind: 'topic' | 'audio' | 'list' | 'queue'}) {
  const {colors} = useApp();
  return <View accessible={false} importantForAccessibility="no-hide-descendants" style={navStyles.icon}>
    {kind === 'topic' ? <><View style={[navStyles.layer, navStyles.backLayer, {borderColor: color}]} /><View style={[navStyles.layer, navStyles.frontLayer, {borderColor: color, backgroundColor: colors.background}]} /></>
      : kind === 'audio' ? <View style={navStyles.bars}>{[8, 17, 23, 14, 8].map((height, i) => <View key={i} style={[navStyles.bar, {height, backgroundColor: color}]} />)}</View>
        : kind === 'queue' ? <View style={[navStyles.clock, {borderColor: color}]}><View style={[navStyles.clockHand, {backgroundColor: color}]} /><View style={[navStyles.clockMinute, {backgroundColor: color}]} /></View>
          : [0, 1, 2].map(i => <View key={i} style={[navStyles.line, {backgroundColor: color}]} />)}
  </View>;
}
const topicIcon = ({color}: {color: string}) => <NavIcon color={color} kind="topic" />;
const podcastIcon = ({color}: {color: string}) => <NavIcon color={color} kind="audio" />;
const playlistIcon = ({color}: {color: string}) => <NavIcon color={color} kind="list" />;
const queueIcon = ({color}: {color: string}) => <NavIcon color={color} kind="queue" />;
function Home() { const {colors} = useApp(); const {state} = useGenerationQueue(); return <Tabs.Navigator initialRouteName="Topics" backBehavior="history" tabBar={AppTabBar}
  screenOptions={{headerShown: false, tabBarActiveTintColor: colors.accent, tabBarInactiveTintColor: colors.muted, tabBarStyle: {backgroundColor: colors.background, borderTopWidth: 0}, tabBarLabelStyle: {fontSize: 12}, tabBarIcon: playlistIcon}}>
  <Tabs.Screen name="Topics" component={TopicsNavigator} options={{title: 'Топики', tabBarIcon: topicIcon}} />
  <Tabs.Screen name="Podcasts" component={PodcastTab} options={{title: 'Подкасты', tabBarIcon: podcastIcon}} />
  <Tabs.Screen name="GenerationQueue" component={GenerationQueueScreen} options={{title: 'Очередь', tabBarIcon: queueIcon, tabBarBadge: state.generationQueue.length || undefined, tabBarBadgeStyle: {backgroundColor: colors.button, color: colors.buttonText}}} />
  <Tabs.Screen name="Playlists" component={PlaylistsNavigator} options={{title: 'Плейлисты'}} />
</Tabs.Navigator>; }
function Navigation() {
  const theme = useNavigationTheme();
  return <NavigationContainer theme={theme}><Root.Navigator>
    <Root.Screen name="Home" component={Home} options={{title: 'PODCAST GENERATOR', headerTitleStyle: {fontSize: 13, fontWeight: '600'}, headerShadowVisible: false, headerRight: renderSettingsAction}} />
    <Root.Screen name="Settings" component={SettingsScreen} options={{title: 'Настройки'}} />
  </Root.Navigator></NavigationContainer>;
}
export default function App() { return <SafeAreaProvider><AppProvider><Navigation /></AppProvider></SafeAreaProvider>; }
